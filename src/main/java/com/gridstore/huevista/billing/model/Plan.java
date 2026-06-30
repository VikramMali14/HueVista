package com.gridstore.huevista.billing.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Plan {

    STARTER(1900, 20, "Starter"),
    PROFESSIONAL(99900, 60, "Professional"),
    BUSINESS(199900, 150, "Business"),
    ENTERPRISE(-1, Integer.MAX_VALUE, "Enterprise");

    private final int priceInPaise;       // -1 = custom pricing
    private final int monthlyAiLimit;     // MAX_VALUE = unlimited
    private final String displayName;

    public double priceInRupees() {
        return priceInPaise / 100.0;
    }
}
