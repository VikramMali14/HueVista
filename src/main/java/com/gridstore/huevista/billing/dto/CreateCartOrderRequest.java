package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * What is in the basket. Quantities and, at most, the code of an offer to try.
 *
 * <p>No prices and no total. The amount is derived server-side from these counts at the
 * catalogue's own rates, because a client that could name the amount could name a rupee and
 * take twenty projects — and the discount is re-derived from the subtotal rather than
 * trusted, so a code that has not been earned takes nothing off however it is sent.
 */
@Data
public class CreateCartOrderRequest {

    /** Projects bought on their own. */
    @Min(value = 0, message = "A quantity cannot be negative.")
    private int projects;

    /** AI image credits bought on their own. */
    @Min(value = 0, message = "A quantity cannot be negative.")
    private int credits;

    /** Combos — a project and the credits that go with it. */
    @Min(value = 0, message = "A quantity cannot be negative.")
    private int combos;

    /**
     * The offer the buyer applied, if any.
     *
     * <p>Optional, and the server does not need it: it works out the best offer the basket
     * has earned either way. It travels so the buyer gets the one they chose when two would
     * both apply, and so a code that has been typed and does not fit can be answered with
     * "this basket doesn't reach ₹589 yet" rather than silently ignored.
     */
    @Size(max = 32, message = "That is not one of our codes.")
    private String discountCode;
}
