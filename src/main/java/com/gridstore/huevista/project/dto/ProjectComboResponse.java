package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.ProjectPdfPage;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * One combination the customer was handed on a colour board — the unit the closing flow
 * asks them to choose between, and the only thing a closed project will still show them.
 */
@Data
@Builder
public class ProjectComboResponse {

    private String id;

    /** Which board it came from and where it sat in it — the order the customer saw. */
    private int boardIndex;
    private int pageIndex;

    private String title;

    /** Whether an AI render of this combo already exists. */
    private boolean rendered;

    private List<Shade> shades;

    @Data
    @Builder
    public static class Shade {
        private Long regionId;
        private String regionLabel;
        private String shadeCode;
        private String shadeName;
        /**
         * The platform-wide HV code for {@link #shadeCode}, when the catalogue has one.
         *
         * Carried so a colour board rebuilt AFTER the studio — the closing sheet that
         * ends with the customer's AI image — can print the same customer-facing code
         * the original did. The render page holds no catalogue of its own, and without
         * this it would have to either fetch nine thousand shades to encode three, or
         * print the manufacturer's raw code and undo the shop's whole numbering scheme.
         */
        private String hvCode;
        private String hex;
    }

    public static ProjectComboResponse from(ProjectPdfPage page, boolean rendered) {
        return from(page, rendered, java.util.Map.of());
    }

    /** @param hvByCode HV codes keyed by upper-cased manufacturer code; may be empty. */
    public static ProjectComboResponse from(ProjectPdfPage page, boolean rendered,
                                            java.util.Map<String, String> hvByCode) {
        return ProjectComboResponse.builder()
                .id(page.getId())
                .boardIndex(page.getBoardIndex())
                .pageIndex(page.getPageIndex())
                .title(page.getTitle())
                .rendered(rendered)
                .shades(page.getShades().stream()
                        .map(s -> Shade.builder()
                                .regionId(s.getRegionId())
                                .regionLabel(s.getRegionLabel())
                                .shadeCode(s.getShadeCode())
                                .shadeName(s.getShadeName())
                                .hvCode(s.getShadeCode() == null ? null
                                        : hvByCode.get(s.getShadeCode().toUpperCase(java.util.Locale.ROOT)))
                                .hex(s.getHexCode())
                                .build())
                        .toList())
                .build();
    }
}
