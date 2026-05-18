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
import java.util.Map;

/**
 * Segments room images for paint visualization.
 *
 * Auto flow (segmentAsync):
 *   1. Try Grounded SAM (text prompt "wall") → precise wall-only masks
 *   2. Fall back to SAM 2 auto-segmentation if Grounded SAM fails
 *
 * Manual flow (segmentPointAndSave):
 *   User clicks a point → SAM 2 point-based segmentation → one wall mask
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

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting wall segmentation: project={}", projectId);

            // Try Grounded SAM first (text-prompted wall detection)
            String predictionId = startGroundedSamPrediction(imageUrl);
            boolean usingGroundedSam = predictionId != null;

            if (!usingGroundedSam) {
                log.warn("Grounded SAM failed, falling back to SAM 2 auto for project {}", projectId);
                predictionId = startPrediction(projectId, imageUrl);
            }

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

            saveRegions(projectId, result, usingGroundedSam);
            markSegmented(projectId);
            log.info("Segmentation complete: project={} groundedSam={}", projectId, usingGroundedSam);

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, e.getMessage());
        }
    }

    /**
     * Calls adirik/grounded-sam on Replicate with prompt "wall" to detect and segment
     * only paintable wall surfaces. Returns the prediction ID or null on failure.
     */
    private String startGroundedSamPrediction(String imageUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Token " + replicateApiToken);

            Map<String, Object> input = Map.of(
                    "image", imageUrl,
                    "prompt", "wall",
                    "box_threshold", 0.3,
                    "text_threshold", 0.25
            );

            Map<String, Object> body = Map.of("input", input);

            ResponseEntity<Map> response = restTemplate.exchange(
                    REPLICATE_BASE + "/models/adirik/grounded-sam/predictions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
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

    private String startPrediction(String projectId, String imageUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "points_per_side", 16,
                "pred_iou_thresh", 0.92,
                "stability_score_thresh", 0.97,
                "min_mask_region_area", 2000,
                "use_m2m", true
        );

        // Use model-based endpoint (no version hash needed) when sam2ModelVersion is blank.
        // POST /models/meta/sam-2/predictions always runs the latest published version.
        Map<String, Object> body = (sam2ModelVersion == null || sam2ModelVersion.isBlank())
                ? Map.of("input", input)
                : Map.of("version", sam2ModelVersion, "input", input);

        try {
            String endpoint = (sam2ModelVersion == null || sam2ModelVersion.isBlank())
                    ? REPLICATE_BASE + "/models/meta/sam-2/predictions"
                    : REPLICATE_BASE + "/predictions";

            ResponseEntity<Map> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            return (String) response.getBody().get("id");
        } catch (Exception e) {
            log.error("Failed to start Replicate prediction: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Synchronously segments a single point and returns the first mask URL.
     * Used for manual point-based segmentation from the frontend.
     */
    public String segmentPoint(String projectId, String imageUrl, double x, double y, String label)
            throws InterruptedException {
        log.info("Point segmentation: project={} x={} y={} label={}", projectId, x, y, label);

        List<List<Double>> inputPoints = List.of(List.of(x * 1024, y * 1024));
        List<Integer> inputLabels = List.of(1);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "input_points", inputPoints,
                "input_labels", inputLabels
        );

        Map<String, Object> body = (sam2ModelVersion == null || sam2ModelVersion.isBlank())
                ? Map.of("input", input)
                : Map.of("version", sam2ModelVersion, "input", input);

        String endpoint = (sam2ModelVersion == null || sam2ModelVersion.isBlank())
                ? REPLICATE_BASE + "/models/meta/sam-2/predictions"
                : REPLICATE_BASE + "/predictions";

        ResponseEntity<Map> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        String predictionId = (String) response.getBody().get("id");
        if (predictionId == null) {
            throw new RuntimeException("Failed to create Replicate prediction for point segmentation");
        }

        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            throw new RuntimeException("Point segmentation timed out or failed");
        }

        // Extract first mask URL from output
        Object output = result.get("output");
        String maskUrl = extractFirstMaskUrl(output);
        if (maskUrl == null) {
            throw new RuntimeException("No mask URL in SAM 2 point segmentation output");
        }

        // Save as a new Region
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

        return regionRepository.save(region).getMaskUrl();
    }

    /**
     * Saves a newly created Region from a point segmentation call, returning the entity.
     */
    public Region segmentPointAndSave(String projectId, String imageUrl, double x, double y, String label)
            throws InterruptedException {
        log.info("Point segmentation: project={} x={} y={} label={}", projectId, x, y, label);

        List<List<Double>> inputPoints = List.of(List.of(x * 1024, y * 1024));
        List<Integer> inputLabels = List.of(1);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "input_points", inputPoints,
                "input_labels", inputLabels
        );

        Map<String, Object> body = (sam2ModelVersion == null || sam2ModelVersion.isBlank())
                ? Map.of("input", input)
                : Map.of("version", sam2ModelVersion, "input", input);

        String endpoint = (sam2ModelVersion == null || sam2ModelVersion.isBlank())
                ? REPLICATE_BASE + "/models/meta/sam-2/predictions"
                : REPLICATE_BASE + "/predictions";

        ResponseEntity<Map> response = restTemplate.exchange(
                endpoint,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        String predictionId = (String) response.getBody().get("id");
        if (predictionId == null) {
            throw new RuntimeException("Failed to create Replicate prediction for point segmentation");
        }

        Map<String, Object> result = pollUntilDone(predictionId);
        if (result == null) {
            throw new RuntimeException("Point segmentation timed out or failed");
        }

        Object output = result.get("output");
        String maskUrl = extractFirstMaskUrl(output);
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

    @SuppressWarnings("unchecked")
    private void saveRegions(String projectId, Map<String, Object> prediction, boolean wallMode) {
        Object output = prediction.get("output");
        if (output == null) {
            log.warn("SAM 2 output is null for project {}", projectId);
            return;
        }

        String outputJson;
        try {
            outputJson = objectMapper.writeValueAsString(output);
        } catch (Exception e) {
            log.error("Failed to serialize SAM 2 output: {}", e.getMessage());
            return;
        }
        log.debug("SAM 2 raw output: {}", outputJson);

        // Grounded SAM output: [{"label":"wall","mask":"url","logit":0.8}, ...]
        // SAM 2 auto output:   {"combined_mask":"url","individual_masks":["url",...]}
        List<Object> items = new ArrayList<>();
        if (output instanceof List<?> list) {
            items.addAll(list);
        } else if (output instanceof Map<?, ?> map && map.containsKey("individual_masks")) {
            Object masks = map.get("individual_masks");
            if (masks instanceof List<?> maskList) items.addAll(maskList);
        } else {
            items.add(output);
        }

        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            try {
                String maskUrl;
                String detectionLabel = null;

                if (item instanceof Map<?, ?> detection) {
                    // Grounded SAM: {"label":"wall","mask":"url","logit":0.8}
                    Object maskVal = detection.get("mask");
                    maskUrl = (maskVal instanceof String s) ? s : null;
                    Object labelVal = detection.get("label");
                    detectionLabel = (labelVal instanceof String s) ? s : null;
                } else {
                    maskUrl = (item instanceof String s) ? s : null;
                }

                String maskData = (maskUrl != null)
                        ? maskUrl
                        : objectMapper.writeValueAsString(item);

                String regionLabel = (detectionLabel != null && !detectionLabel.isBlank())
                        ? capitalize(detectionLabel) + " " + (i + 1)
                        : (wallMode ? "Wall " + (i + 1) : "Region " + (i + 1));

                Region region = Region.builder()
                        .project(projectRepository.getReferenceById(projectId))
                        .label(regionLabel)
                        .maskData(maskData)
                        .maskUrl(maskUrl)
                        .displayOrder(i)
                        .build();
                regions.add(region);
            } catch (Exception e) {
                log.warn("Skipping malformed mask at index {}: {}", i, e.getMessage());
            }
        }

        regionRepository.saveAll(regions);
        log.info("Saved {} regions for project {}", regions.size(), projectId);
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

    private double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
