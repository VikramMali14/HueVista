package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.Project;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ProjectResponse {

    private String id;
    private String name;
    private String status;
    private String imageId;
    private String imageUrl;
    // Populated when status == FAILED so the UI can show the cause.
    private String failureReason;
    private List<RegionResponse> regions;
    private boolean hasShareLink;
    private LocalDateTime shareExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProjectResponse from(Project project, String imageUrl) {
        List<RegionResponse> regions = project.getRegions().stream()
                .map(RegionResponse::from)
                .toList();

        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .failureReason(project.getFailureReason())
                .regions(regions)
                .hasShareLink(project.getShareToken() != null)
                .shareExpiresAt(project.getShareExpiresAt())
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    // Shared/public view — region shade codes hidden
    public static ProjectResponse fromPublic(Project project, String imageUrl) {
        List<RegionResponse> regions = project.getRegions().stream()
                .map(RegionResponse::fromPublic)
                .toList();

        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .regions(regions)
                .build();
    }
}
