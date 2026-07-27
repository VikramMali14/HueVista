package com.gridstore.huevista.hierarchy.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * What the signed-in caller is allowed to see — the one call the frontend makes to
 * decide which nav tabs to render and which pages to let through.
 *
 * Both restrictions follow the same three-state shape, because two states can't say
 * what needs saying:
 * <ul>
 *   <li>{@code restricted = false} — no limit; the list is empty and means nothing.</li>
 *   <li>{@code restricted = true} with a populated list — exactly these.</li>
 *   <li>{@code restricted = true} with an empty list — none, a real revoke-everything.</li>
 * </ul>
 *
 * Non-retailers (admins, distributors, painters, customers) always come back
 * unrestricted: this is a distributor→shop constraint and never applies upward.
 */
@Data
@Builder
public class MyAccessResponse {

    private String role;

    /** The caller's shop org, when they are a retailer who has one. */
    private String orgId;
    private String orgName;

    private boolean brandsRestricted;
    /** Brand display names the shop may work with. Meaningful only when restricted. */
    private List<String> allowedBrands;

    private boolean featuresRestricted;
    /** {@code AppFeature} names the shop may open. Meaningful only when restricted. */
    private List<String> allowedFeatures;

    /** Routes for {@link #allowedFeatures}, so the frontend needn't hardcode the mapping. */
    private List<String> allowedPaths;
}
