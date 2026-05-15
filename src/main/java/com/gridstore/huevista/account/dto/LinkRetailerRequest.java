package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class LinkRetailerRequest {

    @NotBlank
    private String retailerOrgId;

    // Optional override; null = use distributor's default rate
    private BigDecimal commissionRateOverride;
}
