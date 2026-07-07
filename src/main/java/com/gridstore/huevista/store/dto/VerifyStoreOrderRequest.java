package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VerifyStoreOrderRequest {

    @NotBlank
    private String orderId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;
}
