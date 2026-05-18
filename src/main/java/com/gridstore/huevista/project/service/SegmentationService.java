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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Value("${app.claude.api-key:}")
    private String claudeApiKey;

    @Value("${app.claude.model:claude-haiku-4-5-20251001}")
    private String claudeModel;

    private static final String REPLICATE_BASE = "https://api.replicate.com/v1";
    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 60; // 3 min max

    private static final String WALL_POINTS_PROMPT =
            "You are analyzing an interior room image for a paint visualization app. " +
            "Identify 3-5 points that are clearly on paintable wall surfaces — flat painted walls. " +
            "Exclude furniture, floors, ceilings, windows, doors, picture frames, baseboards, and trim. " +
            "Return ONLY a JSON array with no explanation, markdown, or code fences. " +
            "Format: [{\"x\": 0.3, \"y\": 0.5}, ...] where x and y are 0-1 normalized coordinates " +
            "(0,0 is top-left, 1,1 is bottom-right). " +
            "If no clear wall surfaces are visible, return an empty array: []";

    @Async("aiTaskExecutor")
    public void segmentAsync(String projectId, String imageUrl) {
        try {
            log.info("Starting SAM 2 segmentation: project={} image={}", projectId, imageUrl);

            // Try Claude wall detection first
            List<Map<String, Object>> wallPoints = detectWallPoints(imageUrl);

            String predictionId;
            boolean usingWallPoints = wallPoints != null && !wallPoints.isEmpty();

            if (usingWallPoints) {
                log.info("Using Claude wall points for project {}: {} points", projectId, wallPoints.size());
                predictionId = startPredictionWithPoints(projectId, imageUrl, wallPoints);
            } else {
                log.info("Falling back to auto segmentation for project {}", projectId);
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

            saveRegions(projectId, result, usingWallPoints);
            markSegmented(projectId);
            log.info("SAM 2 segmentation complete: project={}", projectId);

        } catch (Exception e) {
            log.error("Segmentation error for project {}: {}", projectId, e.getMessage(), e);
            markFailed(projectId, e.getMessage());
        }
    }

    /**
     * Calls Claude Vision API to detect wall point coordinates in the image.
     * Returns a list of {x, y} maps (normalized 0-1), or null if detection fails.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> detectWallPoints(String imageUrl) {
        try {
            Map<String, Object> imageBlock = Map.of(
                    "type", "image",
                    "source", Map.of(
                            "type", "url",
                            "url", imageUrl
                    )
            );
            Map<String, Object> textBlock = Map.of("type", "text", "text", WALL_POINTS_PROMPT);

            Map<String, Object> requestBody = Map.of(
                    "model", claudeModel,
                    "max_tokens", 256,
                    "messages", List.of(
                            Map.of("role", "user", "content", List.of(imageBlock, textBlock))
                    )
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-api-key", claudeApiKey);
            headers.set("anthropic-version", "2023-06-01");

            ResponseEntity<Map> response = restTemplate.exchange(
                    CLAUDE_API_URL, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    Map.class
            );

            List<Map<String, Object>> content = (List<Map<String, Object>>) response.getBody().get("content");
            String text = ((String) content.get(0).get("text")).trim();
            log.debug("Claude wall points response: {}", text);

            return parseWallPointsJson(text);
        } catch (Exception e) {
            log.warn("Claude wall detection failed, falling back to auto SAM 2: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the JSON array from Claude's response text and parses it into point maps.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseWallPointsJson(String text) {
        try {
            // Extract JSON array from text (handles any surrounding text)
            Pattern pattern = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                log.warn("No JSON array found in Claude response: {}", text);
                return null;
            }
            String json = matcher.group();
            List<Map<String, Object>> points = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (points == null || points.isEmpty()) {
                return null;
            }
            return points;
        } catch (Exception e) {
            log.warn("Failed to parse wall points JSON: {} — text was: {}", e.getMessage(), text);
            return null;
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

    /**
     * Starts a point-based SAM 2 prediction using the provided normalized (0-1) wall points.
     * Points are multiplied by 1024 to produce pixel coords for the model.
     */
    private String startPredictionWithPoints(String projectId, String imageUrl,
                                              List<Map<String, Object>> wallPoints) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Token " + replicateApiToken);

        List<List<Double>> inputPoints = new ArrayList<>();
        List<Integer> inputLabels = new ArrayList<>();
        for (Map<String, Object> pt : wallPoints) {
            double nx = toDouble(pt.get("x"));
            double ny = toDouble(pt.get("y"));
            inputPoints.add(List.of(nx * 1024, ny * 1024));
            inputLabels.add(1);
        }

        Map<String, Object> input = Map.of(
                "image", imageUrl,
                "input_points", inputPoints,
                "input_labels", inputLabels
        );

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
            log.error("Failed to start point-based Replicate prediction: {}", e.getMessage());
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

        // SAM 2 output: {"combined_mask": "url", "individual_masks": ["url", ...]}
        // or for point-based: ["url", ...]
        List<Object> items = new ArrayList<>();
        if (output instanceof Map<?, ?> map && map.containsKey("individual_masks")) {
            Object masks = map.get("individual_masks");
            if (masks instanceof List<?> list) items.addAll(list);
        } else if (output instanceof List<?> list) {
            items.addAll(list);
        } else {
            items.add(output);
        }

        List<Region> regions = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            try {
                String maskUrl = (item instanceof String) ? (String) item : null;
                String maskData = (item instanceof String)
                        ? (String) item
                        : objectMapper.writeValueAsString(item);

                String regionLabel = wallMode
                        ? "Wall " + (i + 1)
                        : "Region " + (i + 1);

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
}
