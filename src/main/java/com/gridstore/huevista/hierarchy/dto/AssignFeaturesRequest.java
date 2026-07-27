package com.gridstore.huevista.hierarchy.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The full set of pages a distributor switches on for one shop. Replaces the shop's
 * current selection wholesale — send every page that should remain open.
 *
 * Deliberately shaped like {@link AssignBrandsRequest}, including the separate
 * {@code unrestricted} flag: an empty {@code features} list means "this shop opens no
 * optional pages", while {@code unrestricted = true} means "no limit, the whole
 * product". Collapsing the two would make revoking a shop's last page grant them
 * everything — the bug that shape was introduced to fix on the brand side.
 */
@Data
public class AssignFeaturesRequest {

    /** {@code AppFeature} names, e.g. {@code ["STUDIO","COLOR_FINDER"]}. */
    private List<String> features = new ArrayList<>();

    /** True = lift the restriction entirely; features is then ignored. */
    private boolean unrestricted = false;
}
