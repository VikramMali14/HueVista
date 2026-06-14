package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class GenerateAccessCodeRequest {

    @NotNull
    @Min(value = 3, message = "Minimum validity is 3 days")
    @Max(value = 14, message = "Maximum validity is 14 days")
    private Integer validDays; // 3, 7, or 14

    // Paint companies (brand display names) to unlock for this guest. Optional —
    // omit or leave empty to let the guest browse every brand.
    private List<String> allowedBrands;
}
