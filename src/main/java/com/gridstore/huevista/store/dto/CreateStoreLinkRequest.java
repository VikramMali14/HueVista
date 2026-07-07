package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateStoreLinkRequest {

    /**
     * Price per image in paise. The floor here is only structural (positive int);
     * the real business minimum (the Rs.50 platform base) is enforced in the
     * service against configuration so it can change without a redeploy.
     */
    @NotNull
    @Min(value = 1, message = "Price is required")
    private Integer pricePaise;

    /** How long each purchased code lasts. Defaults to 3 days when omitted. */
    @Min(value = 3, message = "Minimum validity is 3 days")
    @Max(value = 14, message = "Maximum validity is 14 days")
    private Integer validDays;
}
