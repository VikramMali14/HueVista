package com.gridstore.huevista.paint.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * A paint company that actually has shades in the catalogue, with its shade count.
 * Served by {@code GET /api/shades/brands} so the frontend can build brand pickers
 * dynamically instead of hardcoding company names.
 *
 * <p>Serializable so the {@code @Cacheable} "shade-brands" cache can JDK-serialize
 * it into Redis (same contract as {@link ShadeSummaryResponse}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShadeBrandSummaryResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private String slug;
    private long shadeCount;
}
