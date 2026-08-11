package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.Subscription;
import lombok.Builder;
import lombok.Data;

/**
 * The caller's colour-board PDF allowance, resolved against whichever subscription
 * pays for them (a retailer's own plan; the issuing shop's plan for customers and
 * guests). {@code unlimited} spares clients from comparing against MAX_VALUE.
 */
@Data
@Builder
public class PdfAllowanceResponse {

    /** Most coloured snapshots one PDF board may contain. */
    private int imagesPerPdf;
    private int monthlyLimit;
    private int used;
    private int remaining;
    private boolean unlimited;

    /**
     * The allowance of an account the platform does not bill — see
     * {@code UnbilledAccounts}. No subscription is consulted and none is charged.
     *
     * The per-document image cap still applies: it is a browser-memory guard rather
     * than a commercial limit, which is why even ENTERPRISE carries a finite one.
     */
    public static PdfAllowanceResponse unmetered() {
        return PdfAllowanceResponse.builder()
                .imagesPerPdf(com.gridstore.huevista.billing.model.Plan.ENTERPRISE.getPdfImageLimit())
                .monthlyLimit(Integer.MAX_VALUE)
                .used(0)
                .remaining(Integer.MAX_VALUE)
                .unlimited(true)
                .build();
    }

    public static PdfAllowanceResponse from(Subscription sub) {
        boolean unlimited = sub.getPdfDownloadsLimit() == Integer.MAX_VALUE;
        int remaining = unlimited
                ? Integer.MAX_VALUE
                : Math.max(0, sub.getPdfDownloadsLimit() - sub.getPdfDownloadsUsed());
        return PdfAllowanceResponse.builder()
                .imagesPerPdf(sub.getPdfImageLimit())
                .monthlyLimit(sub.getPdfDownloadsLimit())
                .used(sub.getPdfDownloadsUsed())
                .remaining(remaining)
                .unlimited(unlimited)
                .build();
    }
}
