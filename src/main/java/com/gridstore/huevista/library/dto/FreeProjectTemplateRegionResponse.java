package com.gridstore.huevista.library.dto;

import com.gridstore.huevista.library.model.FreeProjectTemplateRegion;
import lombok.Builder;
import lombok.Data;

/** One wall of a template, with a freshly resolved URL for its mask PNG. */
@Data
@Builder
public class FreeProjectTemplateRegionResponse {

    private Long id;
    private String label;
    private String category;
    private String maskUrl;
    private String appliedHexCode;
    private String appliedShadeCode;
    private int displayOrder;

    public static FreeProjectTemplateRegionResponse from(FreeProjectTemplateRegion r, String maskUrl) {
        return FreeProjectTemplateRegionResponse.builder()
                .id(r.getId())
                .label(r.getLabel())
                .category(r.getCategory() != null ? r.getCategory().name() : null)
                .maskUrl(maskUrl)
                .appliedHexCode(r.getAppliedHexCode())
                .appliedShadeCode(r.getAppliedShadeCode())
                .displayOrder(r.getDisplayOrder())
                .build();
    }
}
