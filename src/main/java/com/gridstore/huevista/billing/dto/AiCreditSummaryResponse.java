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

    /** Credits the plainest AI image costs — the BASIC tier, and the floor the others
     *  are quoted against. Kept for callers that know nothing about the tiers. */
    private int renderCost;

    /**
     * What each quality of image costs, so a client can label the choice without holding
     * the numbers itself.
     *
     * <p>Sent as a list rather than three named fields because the tiers are configuration:
     * a client that iterates this shows whatever the server sells, and one that hard-codes
     * "Pro is 2" starts lying the day that changes.
     */
    private List<RenderTier> renderTiers;

    /**
     * When the soonest batch of credits lapses, and how many go with it. Null and 0 for a
     * wallet holding nothing dated — a shop's credits still never expire, and so does
     * anything bought before the catalogue existed.
     */
    private LocalDateTime soonestExpiryAt;
    private int expiringCredits;

    private String currency;

    private List<ActivityRow> recentActivity;

    /** One quality of AI image and what it costs in credits. */
    @Data
    @Builder
    public static class RenderTier {
        /** BASIC, PRO or MAX — the enum name, so a client can send it straight back. */
        private String quality;
        private int credits;

        public static RenderTier of(String quality, int credits) {
            return RenderTier.builder().quality(quality).credits(credits).build();
        }
    }

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
