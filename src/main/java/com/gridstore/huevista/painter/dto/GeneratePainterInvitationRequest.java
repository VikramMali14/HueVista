package com.gridstore.huevista.painter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class GeneratePainterInvitationRequest {

    @NotNull
    @Min(value = 3, message = "Minimum validity is 3 days")
    @Max(value = 30, message = "Maximum validity is 30 days")
    private Integer validDays;

    /** Optional — restrict redeem to a specific painter's phone number. */
    @Pattern(regexp = "^[+]?[0-9 -]{7,15}$", message = "Phone must be 7–15 digits, optional leading +")
    private String phoneHint;
}
