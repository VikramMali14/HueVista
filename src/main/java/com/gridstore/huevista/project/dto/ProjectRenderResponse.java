package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.ProjectRender;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One AI render, as the studio sees it while it is being made and after it lands.
 *
 * {@code imageUrl} is presigned fresh on every response and is null until the render is
 * READY — the row holds a storage key, and a presigned URL expires, so one baked into the
 * record would be a dead link within the hour.
 */
@Data
@Builder
public class ProjectRenderResponse {

    private String id;

    /** The colour-board combination this was made from. */
    private String comboId;

    /** QUEUED · RUNNING · READY · FAILED — the studio polls until it stops moving. */
    private String status;

    /** The finished image. Null until READY. */
    private String imageUrl;

    /** Why it failed, in a sentence fit to show. Null unless FAILED. */
    private String failureReason;

    private String timeOfDay;
    private String borderMode;
    private String lighting;
    private String furnishing;
    private String style;

    /** PREMIUM · LUXURY — which model made it, and what it cost in credits. Every render
     *  written before the tiers existed reads PREMIUM, which is what it was charged as. */
    private String quality;

    /** CLEANED · ORIGINAL — which photograph of the room the model was given to paint.
     *  Every render written before the choice existed reads CLEANED, which is what it got. */
    private String sourceImage;

    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    public static ProjectRenderResponse from(ProjectRender render, String imageUrl) {
        return ProjectRenderResponse.builder()
                .id(render.getId())
                .comboId(render.getPage() != null ? render.getPage().getId() : null)
                .status(render.getStatus().name())
                .imageUrl(imageUrl)
                .failureReason(render.getFailureReason())
                .timeOfDay(render.getTimeOfDay().name())
                .borderMode(render.getBorderMode().name())
                .lighting(render.getLighting().name())
                .furnishing(render.getFurnishing().name())
                .style(render.getStyle().name())
                .quality((render.getQuality() == null
                        ? ProjectRender.Quality.PREMIUM : render.getQuality()).name())
                .sourceImage((render.getSourceImage() == null
                        ? ProjectRender.SourceImage.CLEANED : render.getSourceImage()).name())
                .note(render.getNote())
                .createdAt(render.getCreatedAt())
                .completedAt(render.getCompletedAt())
                .build();
    }
}
