package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.RewardPointsLot;
import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A shop's reward-point standing: what it can spend, what each thing costs in points,
 * and — because points expire — exactly which batch dies when.
 *
 * The only rupee figure here is what points COST to buy. There is deliberately no
 * "your balance is worth ₹X" — points are spent at point prices, and pricing the balance
 * in money invites treating it as money, which is the one thing it is not.
 */
@Data
@Builder
public class RewardPointsSummaryResponse {

    /** Spendable now — live lots less any refund shortfall still being earned back. */
    private int balance;

    /** Points the shop earns per kiosk sale. */
    private int pointsPerSale;

    /** What buying costs: rupees per point, and the bounds on one purchase. */
    private int rupeesPerPoint;
    private int minPurchase;
    private int maxPurchase;

    /** How long a batch lasts from the day it is earned. */
    private int validityDays;

    /** How many days before expiry the warning e-mail goes out. */
    private int expiryWarningDays;

    /** What points buy, in points. The project price is the CALLER'S rate — it falls
     *  with their plan (80 with none, down to 40 on Business), so it is not a constant. */
    private int projectPrice;
    private int reopenPrice;

    /** The next batch to expire — what the countdown on the panel is about. Null when none. */
    private Integer nextExpiringPoints;
    private LocalDateTime nextExpiryAt;

    /** Every live batch, soonest first. */
    private List<LotRow> lots;

    private List<ActivityRow> recentActivity;

    @Data
    @Builder
    public static class LotRow {
        private String id;
        private int pointsRemaining;
        private LocalDateTime expiresAt;

        public static LotRow from(RewardPointsLot lot) {
            return LotRow.builder()
                    .id(lot.getId())
                    .pointsRemaining(lot.getPointsRemaining())
                    .expiresAt(lot.getExpiresAt())
                    .build();
        }
    }

    @Data
    @Builder
    public static class ActivityRow {
        private String id;
        private int points;
        private String type;
        private LocalDateTime createdAt;

        public static ActivityRow from(RewardPointsTransaction txn) {
            return ActivityRow.builder()
                    .id(txn.getId())
                    .points(txn.getPoints())
                    .type(txn.getType().name())
                    .createdAt(txn.getCreatedAt())
                    .build();
        }
    }
}
