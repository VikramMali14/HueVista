package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What one extra project costs THIS account, on both rails, and what it buys.
 *
 * The price moves with the buyer's plan — 80 points / ₹99 with no plan, down to
 * 40 points / ₹45 on Business — so it is quoted per account rather than stated as a
 * constant anywhere. Points are the cheaper rail on every tier; that gap is the whole
 * reason a shop bothers to top up or run a kiosk.
 */
@Data
@Builder
public class ProjectPurchaseOptionsResponse {

    /** Whether a paid plan is currently covering this account — what sets the rate below. */
    private boolean subscribed;

    /** The plan the price was read off ("FREE" when no paid plan is covering the account). */
    private String pricingPlan;

    /** What one project costs, in points. */
    private int projectPricePoints;

    /** What one project costs in money, in paise, GST included. */
    private int projectPricePaise;

    /** What another window on a lapsed project costs, in points. */
    private int reopenPricePoints;

    /** The account's spendable balance, so the caller can say whether it is enough. */
    private int pointsBalance;

    /** Days of access a purchase (or a reopen) opens. */
    private int validDays;

    /**
     * Projects already paid for and not yet created — the standalone ledger credits a
     * shop between plans holds. A shop WITH a live plan gets its extras added to that
     * plan's allowance instead, where they read as {@code purchasedProjectCredits}.
     */
    private int availableCredits;
}
