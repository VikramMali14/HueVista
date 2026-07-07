package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RedemptionDecisionRequest {

    /** true = approved (admin has paid the UPI id); false = rejected (funds return). */
    @NotNull
    private Boolean approve;

    @Size(max = 1000)
    private String note;
}
