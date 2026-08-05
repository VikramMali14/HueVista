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

    /**
     * DISTRIBUTOR nodes: true for the platform's own "house" distributor, which
     * carries every shop no partner distributor brought in.
     *
     * <p>It is a real distributor organization in the tree but not a distributor
     * ACCOUNT, which is why the distributor total (a count of distributor accounts)
     * does not include it. The flag is here so the report can say so rather than
     * leaving a reader to wonder why the numbers differ by one.
     */
    private boolean house;

    /** Subtree rollups. */
    private long retailerCount;
    private long painterCount;
    /**
     * Customers in this subtree — the walk-ins a shop onboarded with an access code.
     *
     * They are the last link in the chain the report exists to show (distributor →
     * shop → customer), and they used to be represented only as a code count, which
     * says how many were handed out but not who holds one or whether it did anything.
     */
    private long customerCount;
    /** Customer access codes issued / redeemed by shops in this subtree. */
    private long codesIssued;
    private long codesRedeemed;

    // ── CUSTOMER nodes only ───────────────────────────────────────────────
    //
    // A customer's projects are assigned and paid for by their shop, so "how many
    // they were given and how many they have used" is the whole of what there is to
    // read about one — and the pair is what tells a busy shop from a dormant code.

    /** Projects the shop has given this customer. */
    private Integer projectAllowance;

    /** Projects they have actually created. Never decreases — deleting one does not refund it. */
    private Integer projectsUsed;

    /** When their access lapses. Past dates are the point: an expired customer still shows. */
    private LocalDateTime accessExpiresAt;

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
