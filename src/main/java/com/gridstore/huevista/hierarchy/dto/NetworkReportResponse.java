package com.gridstore.huevista.hierarchy.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Role-scoped network report.
 *
 * <ul>
 *   <li>ADMIN — roots are every distributor (retailers + painters nested) plus
 *       retailers not linked to any distributor ("direct" shops).</li>
 *   <li>DISTRIBUTOR — a single root: their own node with their retailers (and
 *       those retailers' painters) nested.</li>
 *   <li>RETAILER — a single root: their shop with its painters nested.</li>
 * </ul>
 */
@Data
@Builder
public class NetworkReportResponse {

    private String viewerRole;

    /** Headline totals for the viewer's scope (distributors, retailers, painters, …). */
    private Map<String, Long> totals;

    private List<NetworkNodeResponse> roots;
}
