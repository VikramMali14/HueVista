package com.gridstore.huevista.paint.dto;

import lombok.Builder;
import lombok.Data;

/** Result of a bulk shade upload: how many rows landed vs were skipped as duplicates. */
@Data
@Builder
public class ShadeUploadResponse {
    private String brand;
    private String slug;
    private int total;
    private int inserted;
    private int skipped;
}
