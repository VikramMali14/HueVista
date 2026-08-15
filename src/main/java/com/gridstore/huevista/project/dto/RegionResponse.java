package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.Region;
import com.gridstore.huevista.project.model.RegionCategory;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RegionResponse {

    private Long id;
    private String label;
    private RegionCategory category;
    private String maskData;
    private String maskUrl;
    private String appliedShadeCode;
    /**
     * The platform-wide code for the applied shade — "HV0348".
     *
     * Safe on a surface where {@link #appliedShadeCode} is not, which is the whole
     * reason it exists: it names no company and no colour, so it can go out on a
     * forwarded link or a printed board, and any HueVista shop can turn it back into
     * a tin. Filled by the caller (it needs a catalogue lookup); null when the applied
     * colour is not a catalogue shade at all.
     */
    private String appliedHvCode;
    private String appliedHexCode;
    private Integer displayOrder;
    private boolean manual;

    public static RegionResponse from(Region region) {
        return RegionResponse.builder()
                .id(region.getId())
                .label(region.getLabel())
                .category(region.getCategory())
                .maskData(region.getMaskData())
                .maskUrl(region.getMaskUrl())
                .appliedShadeCode(region.getAppliedShadeCode())
                .appliedHexCode(region.getAppliedHexCode())
                .displayOrder(region.getDisplayOrder())
                .manual(region.isManual())
                .build();
    }

    /**
     * Shared-link view: the manufacturer's code stays hidden, the HV code does not.
     *
     * The share page used to carry no code at all, which made it the one surface where
     * a customer could show someone the colour but nobody could buy it — the link gets
     * forwarded to a spouse or a builder, and they had a picture and no way to act on
     * it. An HV code fixes that without giving anything away: it is a row number, so
     * the page still names no paint company, and any HueVista shop reads it back.
     * {@code appliedHvCode} is filled by the caller.
     */
    public static RegionResponse fromPublic(Region region) {
        return RegionResponse.builder()
                .id(region.getId())
                .label(region.getLabel())
                .category(region.getCategory())
                .maskData(region.getMaskData())
                .maskUrl(region.getMaskUrl())
                .appliedHexCode(region.getAppliedHexCode()) // hex shown, manufacturer code hidden
                .displayOrder(region.getDisplayOrder())
                .manual(region.isManual())
                .build();
    }
}
