package com.gridstore.huevista.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * A region mask the user drew by hand in the browser (polygon → rasterized PNG).
 * Unlike SAM 2 click-to-segment, this needs no external AI: the client sends the
 * finished mask and we persist it as a region under the chosen category.
 */
@Data
public class CustomMaskRequest {

    /**
     * Base64-encoded PNG mask: white (or any opaque colour) marks the painted
     * area, black/transparent is ignored. Accepts a bare base64 string or a
     * full data URL ("data:image/png;base64,..."), at the image's resolution.
     */
    @NotBlank(message = "maskBase64 is required")
    private String maskBase64;

    /** MAIN_WALL, ACCENT_WALL, TRIM, OTHER_WALL or MANUAL. Defaults to MANUAL. */
    private String category;

    /** Optional display label; defaults to a category-derived name. */
    private String label;
}
