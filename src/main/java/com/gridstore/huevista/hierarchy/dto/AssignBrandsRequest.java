package com.gridstore.huevista.hierarchy.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * The full set of brand ids a distributor grants to one shop. This replaces the
 * shop's current selection wholesale (send every brand that should remain
 * assigned); an empty list clears every restriction and reverts the shop to
 * "all brands".
 */
@Data
public class AssignBrandsRequest {

    private List<Long> brandIds = new ArrayList<>();
}
