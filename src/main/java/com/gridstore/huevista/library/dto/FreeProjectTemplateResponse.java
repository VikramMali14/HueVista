package com.gridstore.huevista.library.dto;

import com.gridstore.huevista.library.model.FreeProjectTemplate;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A template as the gallery shows it. {@code imageUrl} is resolved fresh on every
 * read — in S3 mode it is a presigned URL that would otherwise expire.
 */
@Data
@Builder
public class FreeProjectTemplateResponse {

    private String id;
    private String slug;
    private String title;
    private String space;
    private String roomKey;
    private String roomLabel;
    private String description;
    private String imageUrl;
    private Integer imageWidth;
    private Integer imageHeight;
    private boolean published;
    private int displayOrder;
    private long timesUsed;
    private int regionCount;
    /**
     * How many copies are alive right now and still pointing at this template's
     * stored files — which is exactly how many rooms would go blank if those files
     * were deleted. Distinct from {@code timesUsed}, which only ever counts up:
     * copies people have since deleted are gone from here but not from that.
     */
    private long copiesInUse;
    private List<FreeProjectTemplateRegionResponse> regions;
    private String sourceProjectId;
    private LocalDateTime createdAt;

    public static FreeProjectTemplateResponse from(FreeProjectTemplate t, String imageUrl,
                                                   List<FreeProjectTemplateRegionResponse> regions,
                                                   long copiesInUse) {
        return FreeProjectTemplateResponse.builder()
                .id(t.getId())
                .slug(t.getSlug())
                .title(t.getTitle())
                .space(t.getSpace().name())
                .roomKey(t.getRoomKey())
                .roomLabel(t.getRoomLabel())
                .description(t.getDescription())
                .imageUrl(imageUrl)
                .imageWidth(t.getImageWidth())
                .imageHeight(t.getImageHeight())
                .published(t.isPublished())
                .displayOrder(t.getDisplayOrder())
                .timesUsed(t.getTimesUsed())
                .regionCount(regions.size())
                .copiesInUse(copiesInUse)
                .regions(regions)
                .sourceProjectId(t.getSourceProjectId())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
