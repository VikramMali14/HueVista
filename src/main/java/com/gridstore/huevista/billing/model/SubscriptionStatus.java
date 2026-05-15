package com.gridstore.huevista.billing.model;

public enum SubscriptionStatus {
    CREATED,     // created in our DB, payment not yet completed
    ACTIVE,      // payment captured, subscription live
    HALTED,      // payment failed, Razorpay halted the subscription
    CANCELLED,   // user or admin cancelled; active until period end
    COMPLETED,   // natural end of subscription cycle
    EXPIRED      // period ended and not renewed
}
