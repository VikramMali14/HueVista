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
    private int regionCount;
    private boolean hasShareLink;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProjectSummaryResponse from(Project project, String imageUrl) {
        return ProjectSummaryResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .regionCount(project.getRegions().size())
                .hasShareLink(project.getShareToken() != null)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }
}
