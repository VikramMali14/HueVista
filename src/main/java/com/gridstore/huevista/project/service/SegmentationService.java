package com.gridstore.huevista.project.service;

import tools.jackson.databind.json.JsonMapper;
import com.gridstore.huevista.image.model.ImageType;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Segments room images for paint visualization.
 *
 * <h3>Auto flow (segmentAsync)</h3>
 * <ol>
 *   <li><b>Wall pass:</b> Grounded SAM with positive="wall" and a negative
 *       prompt listing everything we explicitly DON'T want painted: doors,
 *       windows, furniture, electronics, light fixtures, fans, switches,
 *       outlets, AND non-paintable surfaces (tile, marble, granite, brick,
 *       stone). The model subtracts these server-side, so we get a single
 *       wall mask with the obstructions cut out.</li>
 *   <li><b>Component split:</b> Download that mask, threshold to binary, and
 *       run 8-connectivity flood fill. The largest blob becomes the MAIN
 *       wall; the second-largest becomes the ACCENT (highlighter); any
 *       remaining blobs above the noise threshold become OTHER walls. Each
 *       component is re-encoded as its own PNG and uploaded to S3 so the
 *       frontend can paint them independently.</li>
 *   <li><b>Trim pass:</b> A second Grounded SAM call with positive="window
 *       frame, door frame, baseboard, crown molding, picture rail". All
 *       detections are merged into one TRIM region. Failures here don't
 *       fail the whole segmentation — trim is best-effort.</li>
 * </ol>
 *
 * <h3>Manual flow (segmentPointAndSave)</h3>
 * User clicks a point → SAM 2 point-based segmentation → one MANUAL region.
 * Click coordinates are normalized 0–1 and scaled to the image's real pixel
 * dimensions before being sent to SAM 2.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentationService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final StorageService storageService;
    private final RestTemplate restTemplate;
    private final JsonMapper objectMapper;
    private final WallSceneAnalyzer sceneAnalyzer;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.sam2.model-version:}")
    private String sam2ModelVersion;

    @Value("${replicate.grounded-sam.model:schananas/grounded_sam}")
    private String groundedSamModel;

    @Value("${replicate.grounded-sam.model-version:}")
    private String groundedSamModelVersion;

    @Value("${replicate.grounded-sam.detect-trim:true}")
    private boolean detectTrim;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 60;

    /** Maximum number of wall components kept (Main, Accent, plus one Other). */
    private static final int MAX_WALL_REGIONS = 3;

    /**
     * Components below this pixel area are dropped as noise / artifacts.
     * Branched by image type — facades fill more of the frame than indoor
     * shots, so a tighter outdoor threshold catches genuine accent areas
     * while still rejecting JPEG artifacts.
     */
    private static final int MIN_COMPONENT_PIXELS_INDOOR = 5000;
    private static final int MIN_COMPONENT_PIXELS_OUTDOOR = 15000;

    /** Trim pieces are thinner than walls, so a smaller threshold. */
    private static final int MIN_TRIM_PIXELS = 800;

    // --- Wall positive prompts ---------------------------------------------
    private static final String INDOOR_WALL_PROMPT = "interior wall, room wall, painted wall";
    private static final String OUTDOOR_WALL_PROMPT = "exterior wall, building facade, painted facade";

    /**
     * INDOOR fallback for the negative_mask_prompt — used when Claude scene
     * analysis is disabled or fails. Covers openings, decor, fixtures, and
     * non-paintable wall surfaces.
     */
    private static final String INDOOR_FALLBACK_NEGATIVE = String.join(",",
            "door", "window", "windowpane",
            "painting", "picture frame", "mirror", "clock", "shelf", "television",
            "cabinet", "wardrobe", "kitchen cabinet",
            "curtain", "blinds",
            "light switch", "electrical outlet", "thermostat",
            "ceiling light", "ceiling fan", "lamp", "sconce", "chandelier",
            "pendant light", "light bulb", "spotlight",
            "smoke detector", "vent", "air conditioner", "exhaust fan",
            "tile", "ceramic tile", "marble", "granite", "exposed brick",
            "backsplash", "wallpaper", "wood paneling",
            "indoor plant"
    );

    /**
     * OUTDOOR fallback for the negative_mask_prompt — completely different
     * vocabulary from indoor. Sky, vegetation, vehicles, roof, fixtures, and
     * non-paintable exterior cladding.
     */
    private static final String OUTDOOR_FALLBACK_NEGATIVE = String.join(",",
            "sky", "clouds", "tree", "bush", "shrub", "hedge", "plant", "lawn", "grass",
            "road", "sidewalk", "driveway", "fence", "gate",
            "car", "parked car", "vehicle", "motorcycle", "bicycle",
            "door", "front door", "garage door", "window", "balcony",
            "roof", "roof tile", "shingle", "chimney",
            "drainpipe", "gutter", "downspout", "antenna", "satellite dish",
            "air conditioner", "ac unit", "electricity meter", "electrical box",
            "signage", "house number", "mailbox", "lamp post", "street light",
            "exposed brick", "stone cladding", "stone wall", "ceramic tile",
            "wood siding", "vinyl siding", "metal cladding",
            "neighboring building", "background building"
    );

    /**
     * Safety net added to Claude's exclude list so the model never forgets
     * obvious non-paintable surfaces, even if Claude omits them from its
     * scene analysis.
     */
    private static final List<String> INDOOR_SAFETY_EXCLUDES = List.of(
            "door", "window", "ceramic tile", "marble", "exposed brick", "wallpaper");
    private static final List<String> OUTDOOR_SAFETY_EXCLUDES = List.of(
            "sky", "tree", "door", "window", "exposed brick", "stone cladding", "roof");

    // --- Trim positive prompts (image-type-specific vocabulary) ------------
    private static final String INDOOR_TRIM_PROMPT = String.join(",",
            "window frame", "door frame", "baseboard", "skirting board",
            "crown molding", "picture rail", "ceiling trim", "wainscoting");
    private static final String OUTDOOR_TRIM_PROMPT = String.join(",",
            "window frame", "door frame", "fascia", "soffit", "parapet",
            "balcony railing", "trim board", "cornice");

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting wall segmentation: project={}", projectId);

            if (groundedSamModelVersion == null || groundedSamModelVersion.isBlank()) {
                markFailed(projectId,
                        "Auto-segmentation not configured. Set REPLICATE_GROUNDED_SAM_VERSION " +
                        "to a version hash from https://replicate.com/" + groundedSamModel + "/versions, " +
                        "or use click-to-segment to mark walls manually.");
                return;
            }

            String userId = projectRepository.findUserIdById(projectId).orElse(null);
            if (userId == null) {
                markFailed(projectId, "Project owner not found");
                return;
            }

            // Default to INDOOR if the image somehow has no classification —
            // it's the more common path and its prompt list is safer (sky,
            // trees, etc. are rare false positives indoors anyway).
            ImageType imageType = projectRepository.findImageTypeById(projectId)
                    .orElse(ImageType.INDOOR);
            log.info("Segmenting project {} as {}", projectId, imageType);

            // Clear stale auto regions from previous runs (and legacy
            // category=null rows from before the enum existed). MANUAL
            // click-segments are preserved.
            regionRepository.deleteAutoRegionsByProjectId(projectId);

            // Claude pre-analysis: builds a per-image exclude list tailored
            // to what's actually in the photo. Falls back to the static
            // fallback if analysis fails or is disabled — segmentation still
            // runs, just with a less focused negative prompt.
            Optional<WallSceneAnalysis> scene = sceneAnalyzer.analyze(imageUrl, imageType);

            if (scene.isPresent() && !scene.get().paintable()) {
                String material = scene.get().wallMaterial();
                String notes = scene.get().notes();
                markFailed(projectId,
                        "Visible walls are " + (material != null ? material : "not a paintable surface")
                                + " and cannot be repainted. "
                                + (notes != null ? notes + " " : "")
                                + "Try click-to-segment if you want to mark a specific area manually.");
                return;
            }

            String positivePrompt = (imageType == ImageType.OUTDOOR)
                    ? OUTDOOR_WALL_PROMPT : INDOOR_WALL_PROMPT;
            String negativePrompt = buildNegativePrompt(imageType, scene);
            int minWallPixels = (imageType == ImageType.OUTDOOR)
                    ? MIN_COMPONENT_PIXELS_OUTDOOR : MIN_COMPONENT_PIXELS_INDOOR;

            // Pass 1: walls
            List<Region> walls = runWallPass(projectId, userId, imageUrl,
                    positivePrompt, negativePrompt, minWallPixels);
            if (walls.isEmpty()) {
                markFailed(projectId,
                        "No paintable walls detected. The image may be a close-up, " +
                        "or the walls may be fully covered by tile/marble/wallpaper. " +
                        "Try click-to-segment, or upload a wider shot.");
                return;
            }

            // Pass 2: trim (best-effort, doesn't fail the project)
            if (detectTrim) {
                String trimPrompt = (imageType == ImageType.OUTDOOR)
                        ? OUTDOOR_TRIM_PROMPT : INDOOR_TRIM_PROMPT;
                try {
                    runTrimPass(projectId, userId, imageUrl, walls.size(), trimPrompt);
                } catch (Exception e) {
                    log.warn("Trim detection failed for project {} — continuing without trim region: {}",
                            projectId, e.getMessage());
                }
            }

            markSegmented(projectId);
            log.info("Segmentation complete: project={} type={} walls={} trim={}",
                    projectId, imageType, walls.size(), detectTrim);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(projectId, "Segmentation interrupted");
        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, "Segmentation failed: " + e.getMessage());
        }
    }

    /**
     * Builds the Grounded SAM negative_mask_prompt. Prefers Claude's
     * image-specific exclude list (deduped, lowercased, with a safety net of
     * common items added). Falls back to a static type-specific list when
     * scene analysis is unavailable.
     */
    private String buildNegativePrompt(ImageType type, Optional<WallSceneAnalysis> scene) {
        if (scene.isEmpty() || scene.get().excludeObjects().isEmpty()) {
            return (type == ImageType.OUTDOOR)
                    ? OUTDOOR_FALLBACK_NEGATIVE
                    : INDOOR_FALLBACK_NEGATIVE;
        }
        Set<String> combined = new LinkedHashSet<>(scene.get().excludeObjects());
        combined.addAll(type == ImageType.OUTDOOR
                ? OUTDOOR_SAFETY_EXCLUDES
                : INDOOR_SAFETY_EXCLUDES);
        return String.join(",", combined);
    }

    /**
     * Runs Grounded SAM for walls, downloads the resulting mask, applies a
     * morphological close+open to clean up speckle and small holes, splits
     * it into connected components, and persists up to MAX_WALL_REGIONS of
     * them as MAIN_WALL / ACCENT_WALL / OTHER_WALL.
     */
    private List<Region> runWallPass(String projectId, String userId, String imageUrl,
                                     String positivePrompt, String negativePrompt,
                                     int minPixelArea)
            throws Exception {
        String predictionId = startGroundedSamPrediction(imageUrl, positivePrompt, negativePrompt);
        if (predictionId == null) {
            throw new RuntimeException("Failed to start wall segmentation prediction");
        }
        updatePredictionId(projectId, predictionId);

        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            throw new RuntimeException("Wall segmentation timed out");
        }

        String wallMaskUrl = extractGroundedSamMaskUrl(result.get("output"));
        if (wallMaskUrl == null) {
            log.warn("Wall pass returned no mask.jpg for project {}", projectId);
            return List.of();
        }

        byte[] rawMaskBytes = downloadBytes(wallMaskUrl);
        // Morphological cleanup BEFORE component analysis so small holes
        // (where the model missed a wall pixel behind a switch plate) don't
        // become spurious component boundaries.
        byte[] maskBytes;
        try {
            maskBytes = MaskProcessor.morphClean(rawMaskBytes);
        } catch (Exception e) {
            log.warn("Morphological cleanup failed, using raw mask: {}", e.getMessage());
            maskBytes = rawMaskBytes;
        }

        MaskProcessor.MaskAnalysis analysis = MaskProcessor.analyze(maskBytes, minPixelArea);
        if (analysis.components.isEmpty()) {
            log.info("Wall mask had no components above {}px for project {}",
                    minPixelArea, projectId);
            return List.of();
        }

        int keep = Math.min(MAX_WALL_REGIONS, analysis.components.size());
        List<Region> saved = new ArrayList<>(keep);
        for (int i = 0; i < keep; i++) {
            MaskProcessor.Component component = analysis.components.get(i);
            byte[] pngBytes = MaskProcessor.encodeComponentPng(analysis, component);
            String storageKey = storageService.store(
                    pngBytes, userId, "wall-" + (i + 1) + ".png", "image/png");
            String url = storageService.getPublicUrl(storageKey);

            RegionCategory category = (i == 0) ? RegionCategory.MAIN_WALL
                    : (i == 1) ? RegionCategory.ACCENT_WALL
                    : RegionCategory.OTHER_WALL;
            String label = (i == 0) ? "Main Wall"
                    : (i == 1) ? "Accent Wall"
                    : "Wall " + (i + 1);

            Region region = Region.builder()
                    .project(projectRepository.getReferenceById(projectId))
                    .label(label)
                    .category(category)
                    .maskUrl(url)
                    .maskData(url)
                    .displayOrder(i)
                    .build();
            saved.add(regionRepository.save(region));
            log.info("Saved {} region for project {}: storageKey={} areaPx={}",
                    category, projectId, storageKey, component.area);
        }
        return saved;
    }

    /**
     * Runs Grounded SAM with the trim prompt and saves all detected pieces
     * as a single TRIM region. Best-effort — Grounded SAM is known to be
     * weaker on thin objects like baseboards. The prompt vocabulary differs
     * between INDOOR (baseboard, crown molding) and OUTDOOR (fascia, soffit,
     * parapet), so the caller passes in the right one.
     */
    private void runTrimPass(String projectId, String userId, String imageUrl,
                             int displayOrderStart, String trimPrompt)
            throws Exception {
        String predictionId = startGroundedSamPrediction(imageUrl, trimPrompt, "");
        if (predictionId == null) {
            log.warn("Trim pass: failed to start prediction for project {}", projectId);
            return;
        }
        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            log.warn("Trim pass: timed out for project {}", projectId);
            return;
        }
        String trimMaskUrl = extractGroundedSamMaskUrl(result.get("output"));
        if (trimMaskUrl == null) {
            log.info("Trim pass: no mask returned for project {}", projectId);
            return;
        }

        byte[] rawMaskBytes = downloadBytes(trimMaskUrl);
        byte[] maskBytes;
        try {
            maskBytes = MaskProcessor.morphClean(rawMaskBytes);
        } catch (Exception e) {
            log.warn("Morphological cleanup of trim mask failed, using raw: {}", e.getMessage());
            maskBytes = rawMaskBytes;
        }
        MaskProcessor.MaskAnalysis analysis = MaskProcessor.analyze(maskBytes, MIN_TRIM_PIXELS);
        if (analysis.components.isEmpty()) {
            log.info("Trim pass: no components above {}px for project {}",
                    MIN_TRIM_PIXELS, projectId);
            return;
        }

        byte[] combinedPng = MaskProcessor.encodeAllComponentsPng(analysis);
        String storageKey = storageService.store(
                combinedPng, userId, "trim.png", "image/png");
        String url = storageService.getPublicUrl(storageKey);

        Region region = Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Trim & Frames")
                .category(RegionCategory.TRIM)
                .maskUrl(url)
                .maskData(url)
                .displayOrder(displayOrderStart)
                .build();
        regionRepository.save(region);
        log.info("Saved TRIM region for project {}: storageKey={} pieces={}",
                projectId, storageKey, analysis.components.size());
    }

    /**
     * Synchronously segments a single user-clicked point and persists the
     * resulting Region as MANUAL.
     */
    public Region segmentPointAndSave(String projectId, String imageUrl,
                                      int imageWidth, int imageHeight,
                                      double x, double y, String label)
            throws InterruptedException {
        log.info("Point segmentation: project={} x={} y={} size={}x{} label={}",
                projectId, x, y, imageWidth, imageHeight, label);

        double pixelX = x * imageWidth;
        double pixelY = y * imageHeight;
        List<List<Double>> inputPoints = List.of(List.of(pixelX, pixelY));
        List<Integer> inputLabels = List.of(1);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "input_points", inputPoints,
                "input_labels", inputLabels
        );

        String predictionId = startSam2Prediction(input);
        if (predictionId == null) {
            throw new RuntimeException("Failed to create Replicate prediction for point segmentation");
        }
        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            throw new RuntimeException("Point segmentation timed out or failed");
        }
        String maskUrl = extractFirstMaskUrl(result.get("output"));
        if (maskUrl == null) {
            throw new RuntimeException("No mask URL in SAM 2 point segmentation output");
        }

        int displayOrder = regionRepository.countByProjectId(projectId);
        String resolvedLabel = (label != null && !label.isBlank())
                ? label
                : "Region " + (displayOrder + 1);

        Region region = Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label(resolvedLabel)
                .category(RegionCategory.MANUAL)
                .maskUrl(maskUrl)
                .maskData(maskUrl)
                .displayOrder(displayOrder)
                .build();
        return regionRepository.save(region);
    }

    // ---------------------------------------------------------------------
    // Replicate API helpers
    // ---------------------------------------------------------------------

    private String startGroundedSamPrediction(String imageUrl, String positive, String negative) {
        try {
            HttpHeaders headers = jsonHeaders();
            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "mask_prompt", positive,
                    "negative_mask_prompt", negative,
                    "adjustment_factor", 0
            );

            boolean hasPinnedVersion = groundedSamModelVersion != null && !groundedSamModelVersion.isBlank();
            Map<String, Object> body = hasPinnedVersion
                    ? Map.of("version", groundedSamModelVersion, "input", input)
                    : Map.of("input", input);
            String endpoint = hasPinnedVersion
                    ? REPLICATE_BASE + "/predictions"
                    : REPLICATE_BASE + "/models/" + groundedSamModel + "/predictions";

            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            String id = (String) response.getBody().get("id");
            log.info("Grounded SAM prediction started: id={} positive='{}'", id, positive);
            return id;
        } catch (Exception e) {
            log.warn("Failed to start Grounded SAM prediction (positive='{}'): {}", positive, e.getMessage());
            return null;
        }
    }

    private String startSam2Prediction(Map<String, Object> input) {
        HttpHeaders headers = jsonHeaders();
        boolean hasPinnedVersion = sam2ModelVersion != null && !sam2ModelVersion.isBlank();
        Map<String, Object> body = hasPinnedVersion
                ? Map.of("version", sam2ModelVersion, "input", input)
                : Map.of("input", input);
        String endpoint = hasPinnedVersion
                ? REPLICATE_BASE + "/predictions"
                : REPLICATE_BASE + "/models/meta/sam-2/predictions";

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            return (String) response.getBody().get("id");
        } catch (Exception e) {
            log.error("Failed to start SAM 2 prediction: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pollUntilDone(String predictionId) throws InterruptedException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Token " + replicateApiToken);

        for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
            Thread.sleep(POLL_INTERVAL_MS);
            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/predictions/" + predictionId,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class
            );
            Map<String, Object> body = response.getBody();
            String status = (String) body.get("status");
            log.debug("Replicate poll [{}]: status={}", predictionId, status);
            if ("succeeded".equals(status)) return body;
            if ("failed".equals(status) || "canceled".equals(status)) {
                log.warn("Replicate prediction {} ended with status={} error={}",
                        predictionId, status, body.get("error"));
                return null;
            }
        }
        return null;
    }

    /**
     * Picks the binary mask.jpg URL from a schananas/grounded_sam output
     * list — i.e. the raw mask, not the annotated visualization or the
     * inverted mask.
     */
    private static String extractGroundedSamMaskUrl(Object output) {
        if (!(output instanceof List<?> list)) return null;
        for (Object item : list) {
            if (!(item instanceof String url)) continue;
            String path = url.split("\\?", 2)[0];
            if (path.endsWith("/mask.jpg") || path.endsWith("/mask.png")) {
                return url;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractFirstMaskUrl(Object output) {
        if (output instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String url) return url;
        } else if (output instanceof Map<?, ?> map) {
            Object masks = map.get("individual_masks");
            if (masks instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                if (first instanceof String url) return url;
            }
        } else if (output instanceof String url) {
            return url;
        }
        return null;
    }

    private byte[] downloadBytes(String url) {
        ResponseEntity<byte[]> response = restTemplate.exchange(
                url, HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
        byte[] body = response.getBody();
        if (body == null) {
            throw new RuntimeException("Empty response downloading " + url);
        }
        return body;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);
        return headers;
    }

    private void updatePredictionId(String projectId, String predictionId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setReplicatePredictionId(predictionId);
            projectRepository.save(p);
        });
    }

    private void markSegmented(String projectId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.SEGMENTED);
            p.setFailureReason(null);
            projectRepository.save(p);
        });
    }

    private void markFailed(String projectId, String reason) {
        log.error("Segmentation failed for project {}: {}", projectId, reason);
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.FAILED);
            p.setFailureReason(reason);
            projectRepository.save(p);
        });
    }
}
