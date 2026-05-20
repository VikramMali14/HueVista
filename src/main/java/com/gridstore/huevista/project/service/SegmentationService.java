package com.gridstore.huevista.project.service;

import tools.jackson.databind.json.JsonMapper;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Segments room images for paint visualization.
 *
 * Auto flow (segmentAsync):
 *   schananas/grounded_sam (Grounding DINO + SAM) with a positive prompt for
 *   walls and a negative prompt for the surfaces we don't want to paint
 *   (doors, windows, art, TVs, mirrors, cabinets, curtains). The model
 *   subtracts the negatives from the positive mask server-side, so we get a
 *   single clean wall mask without needing to combine masks in Java.
 *
 *   The previous Replicate model (adirik/grounded-sam) was deprecated and
 *   started returning 404, which is why this swap exists. SAM 2 is not used
 *   as an auto fallback — a class-agnostic segmenter floods the canvas with
 *   masks for furniture and decor, which is worse than failing cleanly.
 *
 * Manual flow (segmentPointAndSave):
 *   User clicks a point → SAM 2 point-based segmentation → one region mask.
 *   Click coordinates are normalized 0–1 from the frontend and scaled to the
 *   image's real pixel dimensions (not a 1024×1024 square — that was a bug).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentationService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final RestTemplate restTemplate;
    private final JsonMapper objectMapper;

    @Value("${replicate.api-token:}")
    private String replicateApiToken;

    @Value("${replicate.sam2.model-version:}")
    private String sam2ModelVersion;

    // Grounded SAM model slug (owner/name). Pinning a version is optional but
    // recommended for production stability — without it we run "latest" via
    // /models/{slug}/predictions, which can break when the maintainer republishes.
    @Value("${replicate.grounded-sam.model:schananas/grounded_sam}")
    private String groundedSamModel;

    @Value("${replicate.grounded-sam.model-version:}")
    private String groundedSamModelVersion;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 60;

    // What we WANT painted. Comma-separated for Grounding DINO's text prompts.
    private static final String WALL_PROMPT = "wall";

    // What we explicitly DON'T want painted. The model subtracts these from
    // the wall mask, so doors/windows/art/etc. stay their original color.
    private static final String NON_PAINTABLE_PROMPT =
            "door,window,painting,television,mirror,cabinet,curtain,picture frame,light switch,electrical outlet";

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting wall segmentation: project={}", projectId);

            // Third-party Replicate models (everything except Meta's official
            // SAM 2) only resolve via /predictions with a pinned version hash —
            // the /models/{slug}/predictions shortcut returns 404. Fail fast
            // here so we don't waste an API round-trip and so the user gets a
            // clear error instead of a generic "Failed to create prediction".
            if (groundedSamModelVersion == null || groundedSamModelVersion.isBlank()) {
                markFailed(projectId,
                        "Auto-segmentation not configured. Set REPLICATE_GROUNDED_SAM_VERSION " +
                        "to a version hash from https://replicate.com/" + groundedSamModel + "/versions, " +
                        "or use click-to-segment to mark walls manually.");
                return;
            }

            String predictionId = startWallSegmentationPrediction(imageUrl);
            if (predictionId == null) {
                markFailed(projectId, "Failed to create Replicate prediction");
                return;
            }

            updatePredictionId(projectId, predictionId);

            Map<String, Object> result = pollUntilDone(predictionId);
            if (result == null) {
                markFailed(projectId, "Segmentation timed out or failed");
                return;
            }

            Region wall = saveWallRegion(projectId, result);
            if (wall == null) {
                // Model ran but produced no mask — surface as a failure so the
                // UI can prompt the user to click-segment manually.
                markFailed(projectId, "No walls detected");
                return;
            }
            markSegmented(projectId);
            log.info("Segmentation complete: project={} maskUrl={}", projectId, wall.getMaskUrl());

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, e.getMessage());
        }
    }

    /**
     * Starts a Grounded SAM prediction with positive prompt "wall" and a
     * negative prompt listing surfaces we don't want painted. The model
     * returns one combined wall mask (negatives subtracted). Returns the
     * prediction id or null on failure.
     */
    private String startWallSegmentationPrediction(String imageUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + replicateApiToken);

            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "mask_prompt", WALL_PROMPT,
                    "negative_mask_prompt", NON_PAINTABLE_PROMPT,
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
            log.info("Grounded SAM prediction started: id={} model={} pinnedVersion={}",
                    id, groundedSamModel, hasPinnedVersion);
            return id;
        } catch (Exception e) {
            log.warn("Failed to start Grounded SAM prediction: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Synchronously segments a single point and persists the resulting Region.
     * Coordinates are normalized 0–1 in the frontend; we scale by the image's
     * real pixel size (passed in) before sending to SAM 2.
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
                .maskUrl(maskUrl)
                .maskData(maskUrl)
                .displayOrder(displayOrder)
                .build();

        return regionRepository.save(region);
    }

    private String startSam2Prediction(Map<String, Object> input) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

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
            if ("failed".equals(status) || "canceled".equals(status)) return null;
        }

        return null; // timed out
    }

    /**
     * Parses schananas/grounded_sam output and saves the wall mask as a Region.
     * The model yields four files per prediction: annotated_picture_mask.jpg,
     * neg_annotated_picture_mask.jpg, mask.jpg, inverted_mask.jpg. We want the
     * second one — "mask.jpg" — which is the actual binary mask of wall pixels
     * with the non-paintable surfaces already subtracted.
     */
    private Region saveWallRegion(String projectId, Map<String, Object> prediction) {
        Object output = prediction.get("output");
        if (output == null) {
            log.warn("Grounded SAM output is null for project {}", projectId);
            return null;
        }

        if (log.isDebugEnabled()) {
            try {
                log.debug("Grounded SAM raw output: {}", objectMapper.writeValueAsString(output));
            } catch (Exception ignored) {
                // serialization failure here shouldn't kill segmentation
            }
        }

        String maskUrl = extractWallMaskUrl(output);
        if (maskUrl == null) {
            log.warn("No 'mask.jpg' URL in Grounded SAM output for project {}", projectId);
            return null;
        }

        Region region = Region.builder()
                .project(projectRepository.getReferenceById(projectId))
                .label("Wall")
                .maskUrl(maskUrl)
                .maskData(maskUrl)
                .displayOrder(0)
                .build();
        Region saved = regionRepository.save(region);
        log.info("Saved wall region for project {}: {}", projectId, maskUrl);
        return saved;
    }

    /**
     * Picks the "mask.jpg" URL from a Grounded SAM output list — i.e. the raw
     * binary mask, not the annotated visualization or the inverted mask.
     */
    private static String extractWallMaskUrl(Object output) {
        if (!(output instanceof List<?> list)) return null;
        for (Object item : list) {
            if (!(item instanceof String url)) continue;
            String path = url.split("\\?", 2)[0];
            // Skip the annotated previews and the inverted mask; we want the
            // plain binary "mask.jpg" only.
            if (path.endsWith("/mask.jpg") || path.endsWith("/mask.png")) {
                return url;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractFirstMaskUrl(Object output) {
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

    private void updatePredictionId(String projectId, String predictionId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setReplicatePredictionId(predictionId);
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

    private void markSegmented(String projectId) {
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.SEGMENTED);
            p.setFailureReason(null);
            projectRepository.save(p);
        });
    }
}
