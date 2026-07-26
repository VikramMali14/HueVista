package com.gridstore.huevista.hierarchy.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The full set of brand ids a distributor grants to one shop. Replaces the shop's
 * current selection wholesale — send every brand that should remain assigned.
 *
 * {@code unrestricted} is what an empty {@code brandIds} used to mean implicitly, and
 * splitting them apart is the point: an empty list now means "this shop carries no
 * brands" (a real revoke-everything), while {@code unrestricted = true} means "no limit,
 * browse the whole catalogue". Previously the two were the same request, so revoking a
 * shop's last brand handed them everything instead of nothing.
 */
@Data
public class AssignBrandsRequest {

    private List<Long> brandIds = new ArrayList<>();

    /** True = lift the restriction entirely; brandIds is then ignored. */
    private boolean unrestricted = false;
}
