package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.AiCreditTransaction;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An account's AI image wallet: what is in it, what a credit costs today, and what has
 * moved through it lately.
 *
 * Carries the LIST price beside the price actually charged so the launch offer can be
 * shown honestly — a struck-through ₹198 next to ₹99 — instead of the frontend hard-coding
 * a number that will be wrong the day the offer ends.
 */
@Data
@Builder
public class AiCreditSummaryResponse {

    /** Spendable credits right now. */
    private int balance;

    /** Whether this account may hold credits at all — false for a painter or distributor,
     *  who own no projects and would have nothing to spend them on. */
    private boolean eligible;

    /** What one credit costs today, in paise, after any launch discount. */
    private int pricePaise;

    /** What one credit costs before the discount, in paise. */
    private int listPricePaise;

    /** The launch discount, as a whole percentage. 0 when the offer is over. */
    private int discountPercent;

    /** Bounds on a single top-up. */
    private int minPurchase;
    private int maxPurchase;

    /** Credits one AI image costs. */
    private int renderCost;

    private String currency;

    private List<ActivityRow> recentActivity;

    @Data
    @Builder
    public static class ActivityRow {
        private String id;
        /** Signed: positive is bought or handed back, negative is spent. */
        private int credits;
        private String type;
        private int balanceAfter;
        private String note;
        private LocalDateTime createdAt;

        public static ActivityRow from(AiCreditTransaction txn) {
            return ActivityRow.builder()
                    .id(txn.getId())
                    .credits(txn.getCredits())
                    .type(txn.getType().name())
                    .balanceAfter(txn.getBalanceAfter())
                    .note(txn.getNote())
                    .createdAt(txn.getCreatedAt())
                    .build();
        }
    }
}
