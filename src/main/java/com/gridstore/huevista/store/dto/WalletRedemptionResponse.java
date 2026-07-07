package com.gridstore.huevista.store.dto;

import com.gridstore.huevista.store.model.WalletRedemption;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class WalletRedemptionResponse {
    private String id;
    private String organizationId;
    private String organizationName;
    private int amountPaise;
    private String upiId;
    private String status;
    private String adminNote;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    public static WalletRedemptionResponse from(WalletRedemption r) {
        return WalletRedemptionResponse.builder()
                .id(r.getId())
                .organizationId(r.getOrganization().getId())
                .organizationName(r.getOrganization().getName())
                .amountPaise(r.getAmountPaise())
                .upiId(r.getUpiId())
                .status(r.getStatus().name())
                .adminNote(r.getAdminNote())
                .createdAt(r.getCreatedAt())
                .decidedAt(r.getDecidedAt())
                .build();
    }
}
