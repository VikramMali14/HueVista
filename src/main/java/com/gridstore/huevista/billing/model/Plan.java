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
 * top — see {@link #priceWithTaxInPaise()}. The Razorpay plan configured on
 * the dashboard must be the GST-inclusive amount. Once the image quota is
 * spent, extra images can be bought one at a time at
 * {@link #IMAGE_OVERAGE_PRICE_PAISE} + GST.
 */
@Getter
@RequiredArgsConstructor
public enum Plan {

    // Order matters: ordinal is the upgrade rank (see isUpgradeFrom).
    STARTER(99900, 20, 5, 4, 25, "Starter"),
    PROFESSIONAL(249900, 60, 40, 8, 100, "Professional"),
    BUSINESS(499900, 120, 90, 12, 300, "Business"),
    ENTERPRISE(-1, Integer.MAX_VALUE, Integer.MAX_VALUE, 16, Integer.MAX_VALUE, "Enterprise");

    /** GST charged on every plan and on all pay-per-use overage. */
    public static final int GST_PERCENT = 18;

    /** Base price of ONE extra image once the monthly image quota is spent
     *  (Rs. 50; Rs. 59 with GST — covers the ~Rs. 40 full pipeline cost). */
    public static final int IMAGE_OVERAGE_PRICE_PAISE = 5000;

    /** Base price of ONE extra AI auto-mask run once the monthly auto-mask
     *  allowance is spent (Rs. 25; Rs. 29.50 with GST — covers the ~Rs. 15
     *  model cost). Payable from the prepaid billing wallet. */
    public static final int AUTO_MASK_OVERAGE_PRICE_PAISE = 2500;

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

    /** Base price + 18% GST, in paise (what Razorpay actually bills). -1 for custom pricing. */
    public int priceWithTaxInPaise() {
        if (priceInPaise < 0) return -1;
        return priceInPaise * (100 + GST_PERCENT) / 100;
    }

    public double priceWithTaxInRupees() {
        return priceInPaise < 0 ? -1 : priceWithTaxInPaise() / 100.0;
    }

    /** Price of one extra image incl. GST, in paise (Rs. 59). */
    public static int imageOveragePriceWithTaxInPaise() {
        return IMAGE_OVERAGE_PRICE_PAISE * (100 + GST_PERCENT) / 100;
    }

    /** Price of one extra AI auto-mask run incl. GST, in paise (Rs. 29.50). */
    public static int autoMaskOveragePriceWithTaxInPaise() {
        return AUTO_MASK_OVERAGE_PRICE_PAISE * (100 + GST_PERCENT) / 100;
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
}
