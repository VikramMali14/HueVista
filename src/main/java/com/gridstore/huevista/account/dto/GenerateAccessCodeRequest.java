package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class GenerateAccessCodeRequest {

    @NotNull
    @Min(value = 3, message = "Minimum validity is 3 days")
    @Max(value = 14, message = "Maximum validity is 14 days")
    private Integer validDays; // 3, 7, or 14

    // Paint companies (brand display names) to unlock for this guest. Optional —
    // omit or leave empty to let the guest browse every brand. Bounded so a crafted
    // request can't overflow the comma-joined column or smuggle control characters.
    @Size(max = 20, message = "At most 20 companies can be unlocked")
    private List<@Pattern(regexp = "[\\p{L}\\p{N} .&'-]{1,60}",
            message = "Company names may only contain letters, numbers, spaces and . & ' -") String> allowedBrands;
}
