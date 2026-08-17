package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VerifyStoreOrderRequest {

    @NotBlank
    private String orderId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;

    /**
     * Where to send the receipt, and the customer's way back into the account this
     * purchase opens. Optional — a walk-in may decline, and they still get what they
     * paid for.
     *
     * <p>Deliberately NOT {@code @Email}. This request arrives after the money has
     * moved, so a rejected body here is a customer who has paid and been shown an
     * error. The kiosk validates the address before opening Checkout, where refusing
     * costs nothing; anything malformed that still reaches here is dropped rather than
     * being allowed to fail the payment.
     */
    @Size(max = 320)
    private String email;

    /** What to call the customer in their own studio. Optional. */
    @Size(max = 120)
    private String name;
}
