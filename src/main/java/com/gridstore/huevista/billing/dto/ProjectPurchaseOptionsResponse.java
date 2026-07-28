package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What a project costs this account, in points, and what it buys.
 *
 * One price, not two. Projects used to cost less while a plan was covering the account
 * and more once it lapsed, so this had to quote both ends to stop the second being a
 * surprise. Points do not move with subscription state — they are a shop's own currency
 * and worth the same whatever their plan is doing — so there is a single number here.
 */
@Data
@Builder
public class ProjectPurchaseOptionsResponse {

    /** Whether a plan is currently covering this account. */
    private boolean subscribed;

    /** What one project costs, in points. */
    private int projectPricePoints;

    /** What another window on a lapsed project costs, in points. */
    private int reopenPricePoints;

    /** The account's spendable balance, so the caller can say whether it is enough. */
    private int pointsBalance;

    /** Days of access a purchase (or a reopen) opens. */
    private int validDays;

    /** Projects already paid for and not yet created. */
    private int availableCredits;
}
