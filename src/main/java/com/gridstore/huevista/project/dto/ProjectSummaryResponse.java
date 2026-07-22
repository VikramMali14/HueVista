package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.Project;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ProjectSummaryResponse {

    private String id;
    private String name;
    private String status;
    private String imageId;
    private String imageUrl;
    /** Cleaned (AI photo clean-up) image URL, when one has been produced; null
     *  for projects not yet cleaned. Lets the dashboard show a raw-vs-cleaned
     *  before/after slider without a per-project detail fetch. */
    private String cleanedImageUrl;
    private int regionCount;
    private boolean hasShareLink;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProjectSummaryResponse from(Project project, String imageUrl, String cleanedImageUrl) {
        return ProjectSummaryResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .cleanedImageUrl(cleanedImageUrl)
                .regionCount(project.getRegions().size())
                .hasShareLink(project.getShareToken() != null)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
