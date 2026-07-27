package com.gridstore.huevista.hierarchy.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One account in the network tree (distributor, retailer or painter), with its
 * downline nested in {@code children}. Count fields are rollups over the node's
 * own subtree so a row reads as a report line on its own.
 */
@Data
@Builder
public class NetworkNodeResponse {

    private String userId;
    private String name;
    private String email;
    private String phone;
    /** ADMIN | DISTRIBUTOR | RETAILER | PAINTER — the node's account role. */
    private String role;
    private LocalDateTime joinedAt;

    /** The node's organization (null for painters — they belong to a shop, not an org). */
    private String orgId;
    private String orgName;
    private String city;
    private String state;

    /** Subtree rollups. */
    private long retailerCount;
    private long painterCount;
    /** Customer access codes issued / redeemed by shops in this subtree. */
    private long codesIssued;
    private long codesRedeemed;

    /**
     * Paint brands the distributor has granted this shop (RETAILER nodes only);
     * null on nodes where the concept does not apply.
     *
     * Read it together with {@link #brandsRestricted} — an empty list means "all
     * brands" when the shop is unrestricted and "no brands at all" when it is not,
     * and the list alone cannot tell those apart.
     */
    private List<String> assignedBrands;

    /** RETAILER nodes: whether {@link #assignedBrands} is a limit or just a snapshot. */
    private boolean brandsRestricted;

    /**
     * Labels of the pages the distributor has switched on for this shop (RETAILER
     * nodes only). Same reading rule as {@link #assignedBrands}: pair it with
     * {@link #featuresRestricted}.
     */
    private List<String> assignedFeatures;

    /** RETAILER nodes: whether {@link #assignedFeatures} is a limit or just a snapshot. */
    private boolean featuresRestricted;

    @Builder.Default
    private List<NetworkNodeResponse> children = new ArrayList<>();
}
