package com.gridstore.huevista.paint.dto;

import lombok.Data;

import java.util.List;

/**
 * Public bulk-upload payload. The browser picks a company one of two ways:
 *  - {@code brandSlug} — an existing company chosen from the dropdown, or
 *  - {@code brandName} — a new company typed in (created on the fly).
 * {@code shades} is the uploaded JSON array.
 */
@Data
public class ShadeUploadRequest {

    private String brandSlug;

    private String brandName;

    private List<ShadeUploadItem> shades;

    /**
     * Whether to enrich each new shade with Claude (style tags, mood, finishes,
     * description). Defaults to true when omitted; set false to skip the AI step for a
     * fast, no-cost import.
     */
    private Boolean enrich;
}
