package com.gridstore.huevista.account.dto;

import lombok.Data;

import java.util.List;

/**
 * The shop choosing which paint companies it shows.
 *
 * Mirrors the distributor's {@code AssignBrandsRequest} on purpose — same three-state
 * shape, because two states cannot say what needs saying:
 *
 * <ul>
 *   <li>{@code showAll = true} — no limit of the shop's own; everything the distributor
 *       granted is shown. {@code brandIds} is ignored.</li>
 *   <li>{@code showAll = false} with ids — exactly these companies.</li>
 *   <li>{@code showAll = false} with an empty list — none. A real "show nothing", not a
 *       reset, which is why it cannot be inferred from an empty selection alone.</li>
 * </ul>
 */
@Data
public class SetVisibleBrandsRequest {

    /** Lift the shop's own limit: show every company the distributor granted. */
    private boolean showAll;

    /** The complete selection when {@code showAll} is false. Null is treated as empty. */
    private List<Long> brandIds;
}
