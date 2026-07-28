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
 * Deliberately carries no rupee figure. Points buy at their own prices, and putting a
 * "worth ₹X" next to them would invite the shop to treat them as cash, which is the one
 * thing they are not.
 */
@Data
@Builder
public class RewardPointsSummaryResponse {

    /** Spendable now — live lots less any refund shortfall still being earned back. */
    private int balance;

    /** Points the shop earns per kiosk sale. */
    private int pointsPerSale;

    /** How long a batch lasts from the day it is earned. */
    private int validityDays;

    /** How many days before expiry the warning e-mail goes out. */
    private int expiryWarningDays;

    /** What points buy, in points. */
    private int imagePrice;
    private int autoMaskPrice;
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
