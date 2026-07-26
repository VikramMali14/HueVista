package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Undoing an approved payout. A reason is mandatory (unlike the optional note on a
 * normal decision): the shop is told why money they were shown as paid is back in
 * their balance, and the audit trail has to explain a reversal on its own.
 */
@Data
public class ReverseRedemptionRequest {

    @NotBlank(message = "Say why the payout is being reversed")
    @Size(max = 1000)
    private String note;
}
