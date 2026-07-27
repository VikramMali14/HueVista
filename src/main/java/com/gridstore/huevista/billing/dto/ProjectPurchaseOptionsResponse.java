package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What buying a project costs this account, and what it buys.
 *
 * Both prices are sent, not just today's: the whole point of the lapsed price is that
 * the shop should know about it BEFORE their plan ends, so the panel can say "Rs. 50 now,
 * Rs. 99 once your subscription ends" rather than quietly repricing later.
 */
@Data
@Builder
public class ProjectPurchaseOptionsResponse {

    /** Whether a plan is currently covering this account. */
    private boolean subscribed;

    /** What one more project costs right now, in paise. */
    private int projectPricePaise;

    /** The two ends of that: with a plan, and without one. */
    private int subscribedProjectPricePaise;
    private int unsubscribedProjectPricePaise;

    /** What another window on a lapsed project costs, in paise. */
    private int reopenPricePaise;

    /** Days of access a purchase (or a reopen) opens. */
    private int validDays;

    private String currency;

    /** Projects already paid for and not yet created. */
    private int availableCredits;
}
