package com.gridstore.huevista.billing.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Plan {

    STARTER(1900, 20, 4, 20, "Starter"),
    PROFESSIONAL(99900, 60, 8, 100, "Professional"),
    BUSINESS(199900, 150, 12, 300, "Business"),
    ENTERPRISE(-1, Integer.MAX_VALUE, 16, Integer.MAX_VALUE, "Enterprise");

    private final int priceInPaise;       // -1 = custom pricing
    private final int monthlyAiLimit;     // MAX_VALUE = unlimited
    // Colour-board PDF limits. pdfImageLimit is per DOCUMENT (how many coloured
    // snapshots one board may contain — also a browser-memory guard, so even
    // Enterprise carries a finite cap); monthlyPdfLimit is downloads per billing
    // cycle (MAX_VALUE = unlimited), reset on renewal like the AI quota.
    private final int pdfImageLimit;
    private final int monthlyPdfLimit;
    private final String displayName;

    public double priceInRupees() {
        return priceInPaise / 100.0;
    }
}
