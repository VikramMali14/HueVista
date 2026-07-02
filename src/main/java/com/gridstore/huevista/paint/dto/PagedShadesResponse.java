package com.gridstore.huevista.paint.dto;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * One page of the shade catalogue for {@code GET /api/shades/paged}. The unpaged
 * {@code GET /api/shades} stays for callers that want the whole catalogue in one
 * response; this variant lets clients pull a 10k+ catalogue in slices instead.
 *
 * <p>Serializable so the {@code @Cacheable} "shades" cache can JDK-serialize it
 * into Redis (same contract as {@link ShadeSummaryResponse}).
 */
@Data
@Builder
public class PagedShadesResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<ShadeSummaryResponse> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
