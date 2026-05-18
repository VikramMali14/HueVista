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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Segments room images for paint visualization.
 *
 * Auto flow (segmentAsync):
 *   Grounded SAM with a multi-class indoor prompt — returns labeled detections
 *   for wall, door, window, painting, TV, mirror, cabinet, curtain. Only the
 *   "wall" detections are persisted as paintable Regions today; non-wall
 *   detections are logged for future exclusion-mask use. There is no SAM 2
 *   auto fallback — a class-agnostic segmenter floods the UI with masks for
 *   furniture and decor, which is worse than failing cleanly.
 *
 * Manual flow (segmentPointAndSave):
 *   User clicks a point → SAM 2 point-based segmentation → one wall mask.
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

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 60;

    // Multi-class indoor prompt. GroundingDINO inside Grounded SAM uses
    // period-separated phrases. Each detection comes back with its class label,
    // so we can keep walls and discard the rest.
    private static final String INDOOR_PROMPT =
            "wall . door . window . painting . television . mirror . cabinet . curtain";

    private static final String WALL_LABEL = "wall";

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting wall segmentation: project={}", projectId);

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

            int saved = saveWallRegions(projectId, result);
            if (saved == 0) {
                // Model ran but found no walls — surface as a failure so the UI
                // can prompt the user to click-segment manually.
                markFailed(projectId, "No walls detected");
                return;
            }
            markSegmented(projectId);
            log.info("Segmentation complete: project={} walls={}", projectId, saved);

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, e.getMessage());
        }
    }

    /**
     * Runs adirik/grounded-sam with a multi-class indoor prompt. Returns the
     * prediction id or null on failure.
     */
    private String startWallSegmentationPrediction(String imageUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + replicateApiToken);

            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "prompt", INDOOR_PROMPT,
                    "box_threshold", 0.3,
                    "text_threshold", 0.25
            );

            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/models/adirik/grounded-sam/predictions",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("input", input), headers),
                    Map.class
            );
            String id = (String) response.getBody().get("id");
            log.info("Grounded SAM prediction started: {}", id);
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
     * Parses Grounded SAM output (a list of {label, mask, logit} detections)
     * and persists only the wall detections as paintable Regions. Non-wall
     * detections are logged so we can see in production what the model
     * actually catches — they'll become exclusion masks in a follow-up.
     */
    @SuppressWarnings("unchecked")
    private int saveWallRegions(String projectId, Map<String, Object> prediction) {
        Object output = prediction.get("output");
        if (output == null) {
            log.warn("Grounded SAM output is null for project {}", projectId);
            return 0;
        }

        if (log.isDebugEnabled()) {
            try {
                log.debug("Grounded SAM raw output: {}", objectMapper.writeValueAsString(output));
            } catch (Exception ignored) {
                // serialization failure here shouldn't kill segmentation
            }
        }

        if (!(output instanceof List<?> detections)) {
            log.warn("Grounded SAM output was not a list (got {}) for project {}",
                    output.getClass().getSimpleName(), projectId);
            return 0;
        }

        List<Region> walls = new ArrayList<>();
        List<String> excludedLabels = new ArrayList<>();

        for (Object item : detections) {
            if (!(item instanceof Map<?, ?> detection)) continue;

            String detectionLabel = stringOrNull(detection.get("label"));
            String maskUrl = stringOrNull(detection.get("mask"));
            if (maskUrl == null || detectionLabel == null) continue;

            String normalized = detectionLabel.trim().toLowerCase(Locale.ROOT);
            if (!WALL_LABEL.equals(normalized)) {
                excludedLabels.add(normalized);
                continue;
            }

            int order = walls.size();
            walls.add(Region.builder()
                    .project(projectRepository.getReferenceById(projectId))
                    .label("Wall " + (order + 1))
                    .maskUrl(maskUrl)
                    .maskData(maskUrl)
                    .displayOrder(order)
                    .build());
        }

        if (!excludedLabels.isEmpty()) {
            log.info("Skipped {} non-wall detections for project {}: {}",
                    excludedLabels.size(), projectId, excludedLabels);
        }

        regionRepository.saveAll(walls);
        log.info("Saved {} wall regions for project {}", walls.size(), projectId);
        return walls.size();
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

    private static String stringOrNull(Object v) {
        return (v instanceof String s && !s.isBlank()) ? s : null;
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
            projectRepository.save(p);
        });
    }

    private void markFailed(String projectId, String reason) {
        log.error("Segmentation failed for project {}: {}", projectId, reason);
        projectRepository.findById(projectId).ifPresent(p -> {
            p.setStatus(ProjectStatus.FAILED);
            projectRepository.save(p);
        });
    }
}
