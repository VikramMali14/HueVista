package com.gridstore.huevista.store.model;

/**
 * Lifecycle of a wallet payout request. PENDING holds the requested amount out
 * of the balance (so it can't be double-requested); APPROVED means the admin
 * paid the UPI id manually and the money is gone for good; REJECTED returns the
 * amount to the balance.
 */
public enum WalletRedemptionStatus {
    PENDING,
    APPROVED,
    REJECTED
}
