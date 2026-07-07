package com.gridstore.huevista.store.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** The retailer's kiosk wallet: derived balance plus the recent activity feed. */
@Data
@Builder
public class WalletSummaryResponse {
    private String organizationId;
    private String currency;
    /** Available right now = lifetimeEarned - pending - redeemed. */
    private long balancePaise;
    /** Every retailer share ever accrued from kiosk payments. */
    private long lifetimeEarnedPaise;
    /** Held by redemption requests awaiting an admin decision. */
    private long pendingRedemptionPaise;
    /** Paid out (approved redemptions). */
    private long redeemedPaise;
    /** The platform base kept from every kiosk payment (context for the UI). */
    private int platformFeePaise;
    private List<PaymentRow> recentPayments;
    private List<WalletRedemptionResponse> redemptions;

    @Data
    @Builder
    public static class PaymentRow {
        private String id;
        private int amountPaise;
        private int retailerSharePaise;
        /** The pickup code this payment bought (the shop redeems colours from it). */
        private String code;
        private LocalDateTime createdAt;
    }
}
