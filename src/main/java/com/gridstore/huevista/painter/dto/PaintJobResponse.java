package com.gridstore.huevista.painter.dto;

import com.gridstore.huevista.painter.model.PaintJob;
import com.gridstore.huevista.painter.model.PaintJobStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PaintJobResponse {
    private String id;
    private String projectId;
    private String projectName;
    private String retailerId;
    private String retailerName;
    private String customerId;
    private String customerName;
    private String painterId;
    private String painterName;
    private PaintJobStatus status;
    private String siteAddress;
    private BigDecimal estimatedAreaSqft;
    private BigDecimal estimatedPaintLiters;
    private BigDecimal quotedAmountInr;
    private Integer estimatedDays;
    private LocalDateTime scheduledFor;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String notes;
    private String declineReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PaintJobResponse from(PaintJob j) {
        return PaintJobResponse.builder()
                .id(j.getId())
                .projectId(j.getProject().getId())
                .projectName(j.getProject().getName())
                .retailerId(j.getRetailer().getId())
                .retailerName(j.getRetailer().getName())
                .customerId(j.getCustomer().getId())
                .customerName(j.getCustomer().getName())
                .painterId(j.getPainter() != null ? j.getPainter().getId() : null)
                .painterName(j.getPainter() != null ? j.getPainter().getName() : null)
                .status(j.getStatus())
                .siteAddress(j.getSiteAddress())
                .estimatedAreaSqft(j.getEstimatedAreaSqft())
                .estimatedPaintLiters(j.getEstimatedPaintLiters())
                .quotedAmountInr(j.getQuotedAmountInr())
                .estimatedDays(j.getEstimatedDays())
                .scheduledFor(j.getScheduledFor())
                .startedAt(j.getStartedAt())
                .completedAt(j.getCompletedAt())
                .notes(j.getNotes())
                .declineReason(j.getDeclineReason())
                .createdAt(j.getCreatedAt())
                .updatedAt(j.getUpdatedAt())
                .build();
    }
}
