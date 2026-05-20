package com.gridstore.huevista.project.service;

import tools.jackson.databind.json.JsonMapper;
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
import java.util.List;
import java.util.Map;

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
     * Calibrated for ~1024px-side masks from Grounded SAM — a 5000-pixel blob
     * is roughly 70×70px, smaller than any genuinely paintable wall area.
     */
    private static final int MIN_COMPONENT_PIXELS = 5000;

    /**
     * Trim pieces (baseboards, window frames) are smaller than walls, so we
     * use a lower threshold for the trim pass.
     */
    private static final int MIN_TRIM_PIXELS = 800;

    private static final String WALL_PROMPT = "wall";

    /**
     * Everything that must NOT be painted. Covers: openings (door, window),
     * furniture and decor that hangs ON walls, electrical fixtures, ceiling
     * fixtures, and — most importantly — non-paintable wall surfaces (tile,
     * marble, granite, brick, stone). The model subtracts all of these from
     * the wall mask.
     */
    private static final String NON_PAINTABLE_PROMPT = String.join(",",
            // Openings and frames
            "door", "window", "windowpane",
            // Wall-mounted furniture / decor
            "painting", "picture frame", "mirror", "clock", "shelf", "television",
            // Cabinetry
            "cabinet", "wardrobe", "kitchen cabinet",
            // Soft furnishings
            "curtain", "blinds",
            // Electrical / fixtures
            "light switch", "electrical outlet", "thermostat",
            // Ceiling and wall lighting
            "ceiling light", "ceiling fan", "lamp", "sconce", "chandelier",
            "pendant light", "light bulb", "spotlight",
            // HVAC and detectors
            "smoke detector", "vent", "air conditioner", "exhaust fan",
            // Non-paintable wall surfaces — explicitly subtract so tiled walls
            // in bathrooms / kitchens aren't returned as paintable
            "tile", "tiled wall", "ceramic tile", "marble", "marble wall",
            "granite", "stone wall", "brick wall", "exposed brick",
            "backsplash", "wallpaper", "wood paneling",
            // Misc obstructions
            "plant", "indoor plant"
    );

    private static final String TRIM_PROMPT = String.join(",",
            "window frame", "door frame", "baseboard", "skirting board",
            "crown molding", "picture rail", "ceiling trim", "wainscoting"
    );

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

            // Pass 1: walls
            List<Region> walls = runWallPass(projectId, userId, imageUrl);
            if (walls.isEmpty()) {
                markFailed(projectId,
                        "No paintable walls detected. The image may be a close-up, " +
                        "or the walls may be fully covered by tile/marble/wallpaper. " +
                        "Try click-to-segment, or upload a wider shot.");
                return;
            }

            // Pass 2: trim (best-effort, doesn't fail the project)
            if (detectTrim) {
                try {
                    runTrimPass(projectId, userId, imageUrl, walls.size());
                } catch (Exception e) {
                    log.warn("Trim detection failed for project {} — continuing without trim region: {}",
                            projectId, e.getMessage());
                }
            }

            markSegmented(projectId);
            log.info("Segmentation complete: project={} walls={} trim={}",
                    projectId, walls.size(), detectTrim);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            markFailed(projectId, "Segmentation interrupted");
        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, "Segmentation failed: " + e.getMessage());
        }
    }

    /**
     * Runs Grounded SAM for walls, downloads the resulting mask, splits it
     * into connected components, and persists up to MAX_WALL_REGIONS of them
     * as MAIN_WALL / ACCENT_WALL / OTHER_WALL.
     */
    private List<Region> runWallPass(String projectId, String userId, String imageUrl)
            throws Exception {
        String predictionId = startGroundedSamPrediction(imageUrl, WALL_PROMPT, NON_PAINTABLE_PROMPT);
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

        byte[] maskBytes = downloadBytes(wallMaskUrl);
        MaskProcessor.MaskAnalysis analysis = MaskProcessor.analyze(maskBytes, MIN_COMPONENT_PIXELS);
        if (analysis.components.isEmpty()) {
            log.info("Wall mask had no components above {}px for project {}",
                    MIN_COMPONENT_PIXELS, projectId);
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
     * weaker on thin objects like baseboards.
     */
    private void runTrimPass(String projectId, String userId, String imageUrl, int displayOrderStart)
            throws Exception {
        String predictionId = startGroundedSamPrediction(imageUrl, TRIM_PROMPT, "");
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

        byte[] maskBytes = downloadBytes(trimMaskUrl);
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
