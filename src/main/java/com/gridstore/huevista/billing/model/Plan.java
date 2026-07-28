package com.gridstore.huevista.billing.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Subscription tiers, priced around the real per-image pipeline cost:
 * the compulsory AI photo clean-up (~Rs. 15), the optional AI auto-mask
 * (~Rs. 15) and infra/other charges (~Rs. 5-10) — a fully automatic image
 * costs the business ~Rs. 40, a cleaned image the shop masks by hand
 * ~Rs. 20-25.
 *
 * Two separate monthly quotas fall out of that split:
 * <ul>
 *   <li>{@code monthlyImageLimit} — images processed per cycle. EVERY image
 *       consumes one (the clean-up step is compulsory).</li>
 *   <li>{@code monthlyAutoMaskLimit} — AI wall-detection runs per cycle,
 *       consumed only when the shop chooses the automatic mask after the
 *       clean-up. Manual masking (click-to-segment / hand-drawn) is
 *       unlimited on every tier.</li>
 * </ul>
 *
 * Prices are BASE prices in paise; GST ({@link #GST_PERCENT}) is added on
 * top — see {@link #priceWithTaxInPaise()}. GST is currently 0, so the
 * with-tax amounts equal the base prices. Once a quota is spent, extra images
 * and auto-mask runs are bought with POINTS, priced in
 * {@code PricingService} — a plan tier does not set those.
 */
@Getter
@RequiredArgsConstructor
public enum Plan {

    // Order matters: ordinal is the upgrade rank (see isUpgradeFrom). FREE sits at the
    // bottom so every paid tier reads as an upgrade from it.
    //
    // FREE is what a new shop starts on: three projects to try the whole pipeline end to
    // end — two with AI wall detection, one masked by hand — over a short window. It is
    // sized to be a real trial of the product rather than a usable month of business, so
    // the shop reaches the subscribe decision with the thing actually understood.
    FREE(0, 3, 2, 4, 5, "Free trial"),
    STARTER(99900, 20, 5, 4, 25, "Starter"),
    PROFESSIONAL(249900, 60, 40, 8, 100, "Professional"),
    BUSINESS(499900, 120, 90, 12, 300, "Business"),
    ENTERPRISE(-1, Integer.MAX_VALUE, Integer.MAX_VALUE, 16, Integer.MAX_VALUE, "Enterprise");

    /** GST rate applied to every plan and all pay-per-use overage. Set to 0 for
     *  now — this runs as an individual (non-GST-registered) project, so prices
     *  are billed and shown flat, with no tax added. Restore to 18 to re-enable
     *  GST once the project is registered. */
    public static final int GST_PERCENT = 0;

    private final int priceInPaise;           // base price, -1 = custom pricing
    private final int monthlyImageLimit;      // images processed / cycle (MAX_VALUE = unlimited)
    private final int monthlyAutoMaskLimit;   // AI auto-mask runs / cycle (0 = manual masking only)
    // Colour-board PDF limits. pdfImageLimit is per DOCUMENT (how many coloured
    // snapshots one board may contain — also a browser-memory guard, so even
    // Enterprise carries a finite cap); monthlyPdfLimit is downloads per billing
    // cycle (MAX_VALUE = unlimited), reset on renewal like the image quota.
    private final int pdfImageLimit;
    private final int monthlyPdfLimit;
    private final String displayName;

    public double priceInRupees() {
        return priceInPaise / 100.0;
    }

    /** Base price + GST, in paise (what Razorpay actually bills). GST is
     *  currently 0, so this equals the base price. -1 for custom pricing. */
    public int priceWithTaxInPaise() {
        if (priceInPaise < 0) return -1;
        return priceInPaise * (100 + GST_PERCENT) / 100;
    }

    public double priceWithTaxInRupees() {
        return priceInPaise < 0 ? -1 : priceWithTaxInPaise() / 100.0;
    }

    /** True when switching from {@code current} to this plan is a step UP the
     *  tier ladder — the only in-place plan change we allow while a paid
     *  subscription is active (downgrades wait for the period to end). */
    public boolean isUpgradeFrom(Plan current) {
        return current != null && this.ordinal() > current.ordinal();
    }

    /** True when this tier includes AI auto-masking at all. */
    public boolean autoMaskIncluded() {
        return monthlyAutoMaskLimit > 0;
    }

    /** The starter-for-nothing tier: granted, never sold. */
    public boolean isFree() {
        return this == FREE;
    }
}
