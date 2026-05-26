package com.gridstore.huevista.painter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreatePaintJobRequest {

    @NotBlank
    private String projectId;

    @NotBlank
    private String retailerId;

    /** Painter the retailer wants to assign the job to. Must be linked & ACTIVE. */
    @NotBlank
    private String painterId;

    @Size(max = 500)
    private String siteAddress;

    @Positive
    private BigDecimal estimatedAreaSqft;

    @Positive
    private BigDecimal estimatedPaintLiters;

    @Size(max = 1000)
    private String notes;
}
