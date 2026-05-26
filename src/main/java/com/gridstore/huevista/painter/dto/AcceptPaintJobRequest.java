package com.gridstore.huevista.painter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AcceptPaintJobRequest {

    @NotNull
    @Positive
    private BigDecimal quotedAmountInr;

    @NotNull
    @Min(1)
    private Integer estimatedDays;

    private LocalDateTime scheduledFor;
}
