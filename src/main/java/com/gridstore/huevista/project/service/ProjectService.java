package com.gridstore.huevista.project.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.model.UploadedImage;
import com.gridstore.huevista.image.repository.ImageRepository;
import com.gridstore.huevista.image.service.StorageService;
import com.gridstore.huevista.project.dto.*;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.model.ProjectStatus;
import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.queue.SegmentationJobQueue;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final RegionRepository regionRepository;
    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final StorageService storageService;
    private final SegmentationService segmentationService;

    @Autowired(required = false)
    private SegmentationJobQueue segmentationJobQueue;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Transactional
    public ProjectResponse createProject(String userId, CreateProjectRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        UploadedImage image = imageRepository.findByIdAndUserId(request.getImageId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + request.getImageId()));

        String name = (request.getName() != null && !request.getName().isBlank())
                ? request.getName()
                : "Project " + (projectRepository.findByUserIdOrderByUpdatedAtDesc(userId).size() + 1);

        Project project = projectRepository.save(Project.builder()
                .user(user)
                .image(image)
                .name(name)
                .status(ProjectStatus.CREATED)
                .build());

        log.info("Project created: id={} user={}", project.getId(), userId);
        return ProjectResponse.from(project, storageService.getPublicUrl(image.getStorageKey()));
    }

    @Transactional(readOnly = true)
    public List<ProjectSummaryResponse> getUserProjects(String userId) {
        return projectRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(p -> ProjectSummaryResponse.from(p, storageService.getPublicUrl(p.getImage().getStorageKey())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        return ProjectResponse.from(project, storageService.getPublicUrl(project.getImage().getStorageKey()));
    }

    @Transactional
    public ProjectResponse updateRegionColors(String userId, String projectId, List<RegionColorUpdate> updates) {
        Project project = findOwned(userId, projectId);

        for (RegionColorUpdate update : updates) {
            regionRepository.findByIdAndProjectId(update.getRegionId(), projectId).ifPresent(region -> {
                region.setAppliedShadeCode(update.getShadeCode());
                region.setAppliedHexCode(update.getHexCode());
                regionRepository.save(region);
            });
        }

        return ProjectResponse.from(
                projectRepository.findById(projectId).orElseThrow(),
                storageService.getPublicUrl(project.getImage().getStorageKey())
        );
    }

    @Transactional
    public void deleteProject(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        projectRepository.delete(project);
        log.info("Project deleted: id={} user={}", projectId, userId);
    }

    @Transactional
    public ProjectResponse requestSegmentation(String userId, String projectId) {
        Project project = findOwned(userId, projectId);

        // Allow re-triggering if the previous run never finished (e.g. it
        // crashed, the worker JVM restarted, or an upstream API like Gemini
        // returned a quota / payment error and bubbled out before
        // markFailed could write the status). Without this stale check, a
        // single failed run locks the project out forever and forces the
        // user to reset state by hand. 5 minutes is well past any
        // legitimate segmentation latency (typical run is 10-60s).
        if (project.getStatus() == ProjectStatus.SEGMENTING) {
            java.time.LocalDateTime updatedAt = project.getUpdatedAt();
            boolean stale = updatedAt == null
                    || updatedAt.isBefore(java.time.LocalDateTime.now().minusMinutes(5));
            if (!stale) {
                throw new IllegalStateException("Segmentation already in progress for this project.");
            }
            log.warn("Project {} stuck in SEGMENTING since {}, treating as stale and re-triggering",
                    projectId, updatedAt);
        }

        project.setStatus(ProjectStatus.SEGMENTING);
        project.setFailureReason(null);
        projectRepository.save(project);

        String imageUrl = storageService.getPublicUrl(project.getImage().getStorageKey());
        if (segmentationJobQueue != null) {
            segmentationJobQueue.enqueue(projectId, imageUrl);
        } else {
            segmentationService.segmentAsync(projectId, imageUrl);
        }

        log.info("Segmentation requested: project={}", projectId);
        return ProjectResponse.from(project, imageUrl);
    }

    @Transactional(readOnly = true)
    public ProjectResponse getStatus(String userId, String projectId) {
        Project project = findOwned(userId, projectId);
        return ProjectResponse.from(project, storageService.getPublicUrl(project.getImage().getStorageKey()));
    }

    @Transactional
    public ShareResponse generateShareLink(String userId, String projectId, int validDays) {
        Project project = findOwned(userId, projectId);

        String token = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(validDays);

        project.setShareToken(token);
        project.setShareExpiresAt(expiresAt);
        projectRepository.save(project);

        String shareUrl = baseUrl + "/api/share/" + token;
        log.info("Share link generated: project={} expires={}", projectId, expiresAt);

        return ShareResponse.builder()
                .shareToken(token)
                .shareUrl(shareUrl)
                .expiresAt(expiresAt)
                .build();
    }

    @Transactional(readOnly = true)
    public ProjectResponse getSharedProject(String shareToken) {
        Project project = projectRepository.findByShareToken(shareToken)
                .filter(p -> p.getShareExpiresAt() == null
                        || p.getShareExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found or expired."));

        return ProjectResponse.fromPublic(
                project,
                storageService.getPublicUrl(project.getImage().getStorageKey())
        );
    }

    @Transactional
    public RegionResponse segmentPoint(String userId, String projectId,
                                       double x, double y, String label) {
        Project project = findOwned(userId, projectId);
        UploadedImage image = project.getImage();
        ensureDimensionsCached(image);

        String imageUrl = storageService.getPublicUrl(image.getStorageKey());
        try {
            Region region = segmentationService.segmentPointAndSave(
                    projectId, imageUrl,
                    image.getWidth(), image.getHeight(),
                    x, y, label
            );
            return RegionResponse.from(region);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Point segmentation interrupted", e);
        }
    }

    /**
     * Older uploads (and any future ones we don't measure at upload time) may
     * not have width/height set. SAM 2 needs pixel coordinates, so we read
     * dimensions from storage on demand and persist them back onto the image.
     */
    private void ensureDimensionsCached(UploadedImage image) {
        if (image.getWidth() != null && image.getHeight() != null) return;

        try {
            byte[] bytes = storageService.load(image.getStorageKey());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new IllegalStateException("Unable to decode image: " + image.getStorageKey());
            }
            image.setWidth(decoded.getWidth());
            image.setHeight(decoded.getHeight());
            imageRepository.save(image);
            log.info("Cached dimensions for image {}: {}x{}",
                    image.getId(), decoded.getWidth(), decoded.getHeight());
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image dimensions for " + image.getStorageKey(), e);
        }
    }

    private Project findOwned(String userId, String projectId) {
        return projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }
}
