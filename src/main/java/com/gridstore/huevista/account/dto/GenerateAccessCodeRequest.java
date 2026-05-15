package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GenerateAccessCodeRequest {

    @NotNull
    @Min(value = 3, message = "Minimum validity is 3 days")
    @Max(value = 14, message = "Maximum validity is 14 days")
    private Integer validDays; // 3, 7, or 14
}
