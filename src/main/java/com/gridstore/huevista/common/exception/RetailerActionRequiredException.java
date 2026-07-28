package com.gridstore.huevista.common.exception;

/**
 * The customer has run out of projects, and buying one is NOT the answer — their shop
 * has to add more.
 *
 * A customer onboarded with an access code belongs to a shop: the shop assigned their
 * projects, the shop's quota paid for them, and the shop is standing at a counter they
 * can walk back to. Offering such a customer a "buy another project" button sells them
 * something their shop is already responsible for, and quietly moves the relationship
 * off the counter. So this is a distinct refusal from an ordinary quota error, carrying
 * its own code so the UI points at the shop rather than at Checkout.
 */
public class RetailerActionRequiredException extends RuntimeException {
    public RetailerActionRequiredException(String message) {
        super(message);
    }
}
