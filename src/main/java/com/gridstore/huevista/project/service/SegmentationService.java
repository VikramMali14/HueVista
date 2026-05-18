package com.gridstore.huevista.project.service;

import tools.jackson.core.type.TypeReference;
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
 * Calls the Replicate SAM 2 API to auto-segment an image.
 * Runs asynchronously on the aiTaskExecutor thread pool.
 *
 * Flow:
 *   1. POST /predictions → get prediction ID
 *   2. Poll GET /predictions/{id} every 3 s until succeeded or failed
 *   3. Parse mask output → create Region entities
 *   4. Update project status to SEGMENTED or FAILED
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
    private static final int MAX_POLL_ATTEMPTS = 60; // 3 min max

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting SAM 2 segmentation: project={} image={}", projectId, imageUrl);

            String predictionId = startPrediction(projectId, imageUrl);
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

            saveRegions(projectId, result);
            markSegmented(projectId);
            log.info("SAM 2 segmentation complete: project={}", projectId);

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, e.getMessage());
        }
    }

    private String startPrediction(String projectId, String imageUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "points_per_side", 32,
                "pred_iou_thresh", 0.88,
                "stability_score_thresh", 0.95,
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
    private void saveRegions(String projectId, Map<String, Object> prediction) {
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

        // SAM 2 output can be a list of masks OR a single object with named mask lists
        List<Object> items = new ArrayList<>();
        if (output instanceof List<?> list) {
            items.addAll(list);
        } else if (output instanceof Map<?, ?> map) {
            // e.g. {"masks": [...], "scores": [...]} — treat each top-level value list as items
            map.values().forEach(v -> {
                if (v instanceof List<?> l) items.addAll(l);
                else items.add(v);
            });
            if (items.isEmpty()) items.add(map); // fall back: store whole map as one region
        } else {
            items.add(output);
        }

        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            try {
                String maskData = (item instanceof String)
                        ? objectMapper.writeValueAsString(Map.of("maskUrl", item))
                        : objectMapper.writeValueAsString(item);

                regions.add(Region.builder()
                        .project(projectRepository.getReferenceById(projectId))
                        .label("Region " + (i + 1))
                        .maskData(maskData)
                        .displayOrder(i)
                        .build());
            } catch (Exception e) {
                log.warn("Skipping malformed mask at index {}: {}", i, e.getMessage());
            }
        }

        regionRepository.saveAll(regions);
        log.info("Saved {} regions for project {}", regions.size(), projectId);
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
