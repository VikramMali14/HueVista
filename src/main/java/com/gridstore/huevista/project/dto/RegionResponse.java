package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.Region;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegionResponse {

    private Long id;
    private String label;
    private String maskData;
    private String maskUrl;
    private String appliedShadeCode;
    private String appliedHexCode;
    private Integer displayOrder;

    public static RegionResponse from(Region region) {
        return RegionResponse.builder()
                .id(region.getId())
                .label(region.getLabel())
                .maskData(region.getMaskData())
                .maskUrl(region.getMaskUrl())
                .appliedShadeCode(region.getAppliedShadeCode())
                .appliedHexCode(region.getAppliedHexCode())
                .displayOrder(region.getDisplayOrder())
                .build();
    }

    // Used for shared project view — shade codes are hidden from end customers
    public static RegionResponse fromPublic(Region region) {
        return RegionResponse.builder()
                .id(region.getId())
                .label(region.getLabel())
                .maskData(region.getMaskData())
                .maskUrl(region.getMaskUrl())
                .appliedHexCode(region.getAppliedHexCode()) // hex shown, shade code hidden
                .displayOrder(region.getDisplayOrder())
                .build();
    }
}
