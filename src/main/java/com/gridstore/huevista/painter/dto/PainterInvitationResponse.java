package com.gridstore.huevista.painter.dto;

import com.gridstore.huevista.painter.model.PainterInvitation;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class PainterInvitationResponse {
    private String id;
    private String code;
    private String retailerId;
    private String retailerName;
    private String phoneHint;
    private LocalDateTime expiresAt;
    private boolean used;
    private boolean expired;
    private LocalDateTime usedAt;
    private LocalDateTime createdAt;

    public static PainterInvitationResponse from(PainterInvitation i) {
        return PainterInvitationResponse.builder()
                .id(i.getId())
                .code(i.getCode())
                .retailerId(i.getRetailer().getId())
                .retailerName(i.getRetailer().getName())
                .phoneHint(i.getPhoneHint())
                .expiresAt(i.getExpiresAt())
                .used(i.isUsed())
                .expired(i.isExpired())
                .usedAt(i.getUsedAt())
                .createdAt(i.getCreatedAt())
                .build();
    }
}
