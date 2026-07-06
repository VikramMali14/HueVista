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
    private String roomType;
    private String notes;
    private String status;
    private String imageId;
    private String imageUrl;
    // Cleaned image URL when ImageCleanerService ran. Frontend should
    // prefer this as the paint canvas when present — masks are aligned
    // to the cleaned image, not the original. Null when cleaning is
    // disabled or hasn't run.
    private String cleanedImageUrl;
    // Populated when status == FAILED so the UI can show the cause.
    private String failureReason;
    private List<RegionResponse> regions;
    private boolean hasShareLink;
    private LocalDateTime shareExpiresAt;
    // When the customer sent the project to the issuing shop; null until then.
    private LocalDateTime sentToShopAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProjectResponse from(Project project, String imageUrl) {
        List<RegionResponse> regions = project.getRegions().stream()
                .map(RegionResponse::from)
                .toList();

        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .roomType(project.getRoomType())
                .notes(project.getNotes())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .failureReason(project.getFailureReason())
                .regions(regions)
                .hasShareLink(project.getShareToken() != null)
                .shareExpiresAt(project.getShareExpiresAt())
                .sentToShopAt(project.getSentToShopAt())
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
                // The guest needs this to render "Sent ✓" after a reload.
                .sentToShopAt(project.getSentToShopAt())
                .build();
    }
}
