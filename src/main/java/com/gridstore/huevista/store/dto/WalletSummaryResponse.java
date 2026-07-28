package com.gridstore.huevista.store.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The shop's kiosk statement: what the link sold, and the reward points those sales
 * earned. Points are spending power inside HueVista (1 point = 1 paise) and are never
 * paid out as cash, so there is no payout balance or redemption history here.
 */
@Data
@Builder
public class WalletSummaryResponse {
    private String organizationId;
    private String currency;
    /**
     * Points available to spend right now, read from the OWNER's point ledger. Lower than
     * the lifetime figure whenever a batch has expired or been spent.
     */
    private int pointsBalance;
    /** Every point this shop's kiosk has ever earned, refunded sales excluded. */
    private long lifetimePointsEarned;
    /** What one kiosk sale earns the shop right now (context for the UI). */
    private int pointsPerSale;
    /** What a walk-in pays at the kiosk right now (context for the UI). */
    private int kioskPricePaise;
    private List<PaymentRow> recentPayments;

    @Data
    @Builder
    public static class PaymentRow {
        private String id;
        /** What the walk-in paid. All of it is HueVista's — the shop earns points, not a share. */
        private int amountPaise;
        /** Points this sale earned the shop. */
        private int bonusPoints;
        /** Refunded or charged back — the points were taken back. */
        private boolean reversed;
        /** The pickup code this payment bought (the shop redeems colours from it). */
        private String code;
        private LocalDateTime createdAt;
    }
}
