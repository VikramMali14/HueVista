package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** Partial update — only the provided fields change. */
@Data
public class UpdateStoreLinkRequest {

    @Min(value = 1, message = "Price must be positive")
    private Integer pricePaise;

    @Min(value = 3, message = "Minimum validity is 3 days")
    @Max(value = 14, message = "Maximum validity is 14 days")
    private Integer validDays;

    private Boolean active;
}
