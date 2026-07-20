package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.BillingWallet;
import com.gridstore.huevista.billing.model.BillingWalletTransaction;
import com.gridstore.huevista.billing.model.Plan;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** The prepaid billing wallet: balance, what overage costs, and the recent statement. */
@Data
@Builder
public class BillingWalletSummaryResponse {

    private long balancePaise;
    private String currency;
    /** GST-inclusive pay-per-use prices the wallet is spent on. */
    private int imageCreditPricePaise;
    private int autoMaskCreditPricePaise;
    private List<TransactionRow> transactions;

    @Data
    @Builder
    public static class TransactionRow {
        private String id;
        private String type;       // TOPUP | EXTRA_IMAGE | EXTRA_AUTO_MASK
        private long amountPaise;  // signed: + top-up, - purchase
        private LocalDateTime createdAt;
    }

    public static BillingWalletSummaryResponse from(BillingWallet wallet,
                                                    List<BillingWalletTransaction> transactions) {
        return BillingWalletSummaryResponse.builder()
                .balancePaise(wallet != null ? wallet.getBalancePaise() : 0)
                .currency("INR")
                .imageCreditPricePaise(Plan.imageOveragePriceWithTaxInPaise())
                .autoMaskCreditPricePaise(Plan.autoMaskOveragePriceWithTaxInPaise())
                .transactions(transactions.stream()
                        .map(t -> TransactionRow.builder()
                                .id(t.getId())
                                .type(t.getType().name())
                                .amountPaise(t.getAmountPaise())
                                .createdAt(t.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }
}
