package com.gridstore.huevista.painter.dto;

import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.model.PainterRetailerLink;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PainterRetailerLinkResponse {
    private String id;
    private String painterId;
    private String painterName;
    private String retailerId;
    private String retailerName;
    private PainterLinkStatus status;
    private BigDecimal commissionPct;
    private LocalDateTime acceptedAt;
    private LocalDateTime createdAt;

    public static PainterRetailerLinkResponse from(PainterRetailerLink l) {
        return PainterRetailerLinkResponse.builder()
                .id(l.getId())
                .painterId(l.getPainter().getId())
                .painterName(l.getPainter().getName())
                .retailerId(l.getRetailer().getId())
                .retailerName(l.getRetailer().getName())
                .status(l.getStatus())
                .commissionPct(l.getCommissionPct())
                .acceptedAt(l.getAcceptedAt())
                .createdAt(l.getCreatedAt())
                .build();
    }
}
