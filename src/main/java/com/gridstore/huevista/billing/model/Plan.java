package com.gridstore.huevista.billing.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Subscription tiers, priced around the real per-project pipeline cost: the compulsory
 * AI photo clean-up (~Rs. 15), the AI auto-mask (~Rs. 15) and infra/other charges
 * (~Rs. 5-10).
 *
 * A PROJECT is the single billable unit. It covers the whole automatic pipeline —
 * clean-up AND wall detection — so a shop buys one thing and gets the finished result,
 * rather than budgeting two quotas that could run out independently and leave a cleaned
 * photo with no way to mask it. Manual masking (click-to-segment / hand-drawn) stays
 * free and unlimited on every tier, as does everything done inside a project once it
 * exists (recolouring, palette suggestions, sharing).
 *
 * Once {@code monthlyProjectLimit} is spent, extra projects are bought one at a time at
 * the tier's own rate — {@link #getExtraProjectPoints()} in points, or
 * {@link #getExtraProjectPricePaise()} in money. The bigger the plan, the cheaper the
 * extra, so a shop that outgrows its tier is nudged up rather than penalised. An account
 * with no paid plan (including one on the free tier) pays the FREE tier's rate, which is
 * the dearest — subscribing is always the cheaper way to buy volume.
 *
 * Plan prices are BASE prices in paise; GST ({@link #GST_PERCENT}) is added on top —
 * see {@link #priceWithTaxInPaise()}. GST is currently 0, so the with-tax amounts equal
 * the base prices.
 */
@Getter
@RequiredArgsConstructor
public enum Plan {

    // Order matters: ordinal is the upgrade rank (see isUpgradeFrom). FREE sits at the
    // bottom so every paid tier reads as an upgrade from it.
    //
    // FREE is a PERMANENT tier, not a countdown. Every shop keeps two complete projects a
    // month for as long as the account exists, renewing on the same monthly cycle a paid
    // plan does. It replaced a seven-day trial that expired into nothing: a shop that
    // photographed one room in a quiet week lost the product entirely and had to ask
    // support (or its distributor) to be let back in, which made the free tier a deadline
    // to manage rather than a way in. Two a month is small enough that a working counter
    // outgrows it in days and large enough that the shop is never locked out while
    // deciding.
    //
    // What the free tier does NOT include is colour matching — the Colour finder, which
    // pulls catalogue shade codes out of any photograph. It is the one tool that is
    // valuable on its own, without a project behind it, so leaving it on the free tier
    // meant the most useful thing at the counter cost nothing and nothing above it needed
    // buying. Everything else the free tier reaches is the same product the paid tiers get.
    FREE(0, 2, 4, 5, 80, 9900, false, "Free"),
    STARTER(99900, 15, 4, 25, 60, 6500, true, "Starter"),
    PROFESSIONAL(249900, 45, 8, 100, 50, 5500, true, "Professional"),
    BUSINESS(499900, 100, 12, 300, 40, 4500, true, "Business"),
    // Enterprise quota is unlimited, so its extra-project rate is only ever quoted, never
    // charged. It mirrors Business so a quote never reads as dearer than the tier below.
    ENTERPRISE(-1, Integer.MAX_VALUE, 16, Integer.MAX_VALUE, 40, 4500, true, "Enterprise");

    /** GST rate applied to every plan and all pay-per-use overage. Set to 0 for
     *  now — this runs as an individual (non-GST-registered) project, so prices
     *  are billed and shown flat, with no tax added. Restore to 18 to re-enable
     *  GST once the project is registered. */
    public static final int GST_PERCENT = 0;

    private final int priceInPaise;           // base price, -1 = custom pricing
    private final int monthlyProjectLimit;    // complete projects / cycle (MAX_VALUE = unlimited)
    // Colour-board PDF limits. pdfImageLimit is per DOCUMENT (how many coloured
    // snapshots one board may contain — also a browser-memory guard, so even
    // Enterprise carries a finite cap); monthlyPdfLimit is downloads per billing
    // cycle (MAX_VALUE = unlimited), reset on renewal like the project quota.
    private final int pdfImageLimit;
    private final int monthlyPdfLimit;
    /** Points one extra project costs on this tier, once the monthly quota is spent. */
    private final int extraProjectPoints;
    /** What one extra project costs in paise when paid with money instead of points. */
    private final int extraProjectPricePaise;
    /**
     * Whether this tier includes colour matching — the Colour finder, which reads any
     * photograph and answers with the nearest catalogue shade codes.
     *
     * The only tool this product gates on the tier rather than on a quota, because it is
     * the only one that produces something a shop can sell from without ever creating a
     * project: a counter can answer "what shade is this?" all day and never touch the
     * allowance the tiers are actually priced on.
     */
    private final boolean colorMatching;
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

    /** One extra project's cash price + GST, in paise (what Razorpay actually bills). */
    public int extraProjectPriceWithTaxInPaise() {
        return extraProjectPricePaise * (100 + GST_PERCENT) / 100;
    }

    /** True when switching from {@code current} to this plan is a step UP the
     *  tier ladder — the only in-place plan change we allow while a paid
     *  subscription is active (downgrades wait for the period to end). */
    public boolean isUpgradeFrom(Plan current) {
        return current != null && this.ordinal() > current.ordinal();
    }

    /** The starter-for-nothing tier: granted with the account and renewed monthly, never
     *  sold. Also the rate an account with no plan at all pays for a one-off project. */
    public boolean isFree() {
        return this == FREE;
    }
}
