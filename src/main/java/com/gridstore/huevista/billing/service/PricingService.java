package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One place that answers "what does this cost?".
 *
 * A shop buys four things: a monthly plan, points, extra projects once the plan's monthly
 * allowance is spent, and AI image credits. A CUSTOMER buys exactly one of them — AI image
 * credits — because everything else on the list is shop-side and a customer's projects are
 * given to them by a shop. The extra project is the only one with two prices — points
 * or money — and both are read off the buyer's own PLAN rather than being flat: the
 * bigger the tier, the cheaper the extra, so a shop that keeps outgrowing its plan is
 * nudged up a tier instead of paying a flat premium forever. An account with no paid plan
 * (a lapsed one, or one still on the free trial) pays the FREE tier's rate, the dearest.
 *
 * Cash amounts are in paise (Rs. 1 = 100 paise) to match Razorpay; point prices are in
 * whole points. Points are the cheaper rail by design — 80 points against ₹199 with no
 * plan — because they are bought in bulk or earned at the kiosk.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final BillingService billingService;
    private final UnbilledAccounts unbilledAccounts;
    private final com.gridstore.huevista.account.repository.OrgMembershipRepository membershipRepository;

    /** Days of access a purchased project (or a reopen) opens. */
    @Value("${app.project-credit.valid-days:30}")
    private int projectValidDays;

    /**
     * What a walk-in pays at any kiosk — one flat price across the platform, whatever the
     * shop's plan is doing and whatever shop it is.
     *
     * The shop does not set this and takes no share of it: the walk-in is HueVista's own
     * customer, so the whole amount is ours for our own service. The shop is rewarded in
     * closed-loop points instead ({@link #kioskBonusPoints()}). Letting the shop
     * price the link and keep the excess is what made this a third-party collection, and
     * that is the thing this design removes.
     */
    @Value("${app.store.price-paise:19900}")
    private int kioskPricePaise;

    /** Reward points awarded to the shop whose link made the sale. */
    @Value("${app.store.bonus-points:30}")
    private int kioskBonusPoints;

    // The point price list — the only prices these things have. Every quote of one in
    // the product reads from here.
    /** Rupees per point when buying. One rupee, one point. */
    @Value("${app.points.rupees-per-point:1}")
    private int rupeesPerPoint;

    @Value("${app.points.min-purchase:100}")
    private int pointsMinPurchase;

    @Value("${app.points.max-purchase:100000}")
    private int pointsMaxPurchase;

    /** What a reopen costs in money. Flat, like its point price — a reopen buys another
     *  window on work already paid for once, so there is nothing tier-shaped about it. */
    @Value("${app.project-credit.reopen-price-paise:900}")
    private int reopenPricePaise;

    @Value("${app.points.reopen:9}")
    private int pointsPriceReopen;

    /** What reopening a CLOSED project costs — half a project, against a lapsed window's
     *  flat ₹9. See {@link #reopenPricePaise(boolean)} for why the two differ. */
    @Value("${app.project-credit.reopen-closed-price-paise:9900}")
    private int reopenClosedPricePaise;

    @Value("${app.points.reopen-closed:99}")
    private int pointsPriceReopenClosed;

    /** A second AI render on a project that already spent its included one. Flat. */
    @Value("${app.render.top-up-price-paise:9900}")
    private int renderTopUpPricePaise;

    // ── AI image credits ────────────────────────────────────────────────────
    //
    // The wallet rail for the same picture the per-project top-up above buys. One credit
    // is one AI image, and the two prices are kept deliberately equal at launch (₹198 less
    // 50% is ₹99) so a customer can never be worse off for having topped up in advance.

    /** The undiscounted price of one AI image credit, in paise. */
    @Value("${app.ai-credit.list-price-paise:19800}")
    private int aiCreditListPricePaise;

    /**
     * The launch discount on AI credits, as a whole percentage off the list price.
     *
     * A percentage rather than a second "launch price" setting, because the two would drift
     * — somebody would move the list price and leave the launch price where it was, and the
     * strike-through the customer is shown would then be a lie. Set to 0 to end the offer.
     */
    @Value("${app.ai-credit.launch-discount-percent:50}")
    private int aiCreditDiscountPercent;

    @Value("${app.ai-credit.min-purchase:1}")
    private int aiCreditMinPurchase;

    @Value("${app.ai-credit.max-purchase:50}")
    private int aiCreditMaxPurchase;

    /** Credits one AI image costs. One, and there is no reason for it to be anything else —
     *  it is here so the number is quoted from one place rather than typed at three. */
    @Value("${app.ai-credit.render-cost:1}")
    private int aiCreditRenderCost;

    @Value("${app.points.validity-days:365}")
    private int pointsValidityDays;

    @Value("${app.points.expiry-warning-days:10}")
    private int pointsExpiryWarningDays;

    @Value("${app.project-credit.currency:INR}")
    private String currency;

    /**
     * Is this account's work covered right now?
     *
     * An administrator always is, without holding a subscription — see
     * {@link UnbilledAccounts}. Answering "no" here was what put an admin's own projects
     * behind a validity window and a reopen button.
     */
    public boolean isSubscribed(String userId) {
        return unbilledAccounts.covers(userId)
                || billingService.findEntitlingSubscription(userId).isPresent();
    }

    /**
     * What {@code points} cost in paise. The only arithmetic that converts between the
     * two units, so a change to the rate lands everywhere at once.
     */
    public int pointsPricePaise(int points) {
        return points * rupeesPerPoint * 100;
    }

    public int rupeesPerPoint() {
        return rupeesPerPoint;
    }

    public int pointsMinPurchase() {
        return pointsMinPurchase;
    }

    public int pointsMaxPurchase() {
        return pointsMaxPurchase;
    }

    public int projectValidDays() {
        return projectValidDays;
    }

    /** What one kiosk visualisation costs a walk-in. Flat across the platform. */
    public int kioskPricePaise() {
        return kioskPricePaise;
    }

    /** Reward points the shop earns per kiosk sale. */
    public int kioskBonusPoints() {
        return kioskBonusPoints;
    }

    /**
     * The plan an account buys extras at. A live PAID plan sets its own rate; anything
     * else — no plan, a lapsed one, or a free trial — pays the FREE tier's, which is the
     * dearest. A trial is deliberately excluded: it is granted, not bought, so quoting it
     * a paid tier's discount would hand out the benefit of a subscription nobody paid for.
     */
    public Plan pricingPlanFor(String userId) {
        return billingService.findEntitlingSubscription(userId)
                .filter(s -> !s.isTrial())
                .map(Subscription::getPlan)
                .orElse(Plan.FREE);
    }

    /** What one extra project costs this account in points, at its plan's rate. */
    public int pointsPriceProject(String userId) {
        return pricingPlanFor(userId).getExtraProjectPoints();
    }

    /** What one extra project costs this account in paise (GST included), at its plan's rate. */
    public int projectPricePaise(String userId) {
        return pricingPlanFor(userId).extraProjectPriceWithTaxInPaise();
    }

    /** Projects a bundle grants. */
    public static final int BUNDLE_CREDITS = 3;

    /** Projects a bundle charges for. */
    public static final int BUNDLE_PAID_FOR = 2;

    /**
     * What three projects cost bought together, in paise (GST included), at this account's
     * rate. Two projects' money for {@link #BUNDLE_CREDITS} projects.
     *
     * The discount is expressed as a smaller price for a fixed quantity rather than as a
     * free credit granted after the second purchase, because the two behave differently
     * when someone stops buying: a bundle is settled the moment it is paid for, while an
     * earn-your-third rule leaves an obligation hanging over an account that may never
     * come back. It also quotes honestly — the buyer sees ₹398 before paying it, not a
     * promise about a purchase they have not made yet.
     */
    public int bundlePricePaise(String userId) {
        return projectPricePaise(userId) * BUNDLE_PAID_FOR;
    }

    /** What one reopen costs in paise, GST included, for a project whose window lapsed. */
    public int reopenPricePaise() {
        return reopenPricePaise(false);
    }

    /**
     * What reopening this project costs in paise, GST included.
     *
     * Two different purchases wear the same name. A LAPSED project ran out of days with
     * the work unfinished, and ₹9 buys the clock back. A CLOSED one is finished — the
     * customer took their colour board and an AI render off it and said so — and reopening
     * unlocks the whole catalogue again on a job that already delivered. That is half a
     * project's worth of product, so it is priced at half a project.
     *
     * Both the order and the verify side must call this with the same project, or a
     * correctly-paid reopen fails signature verification on an amount mismatch.
     */
    public int reopenPricePaise(boolean closed) {
        int base = closed ? reopenClosedPricePaise : reopenPricePaise;
        return base * (100 + Plan.GST_PERCENT) / 100;
    }

    public int pointsPriceReopen() {
        return pointsPriceReopen(false);
    }

    /** The points twin of {@link #reopenPricePaise(boolean)}, on the same split. */
    public int pointsPriceReopen(boolean closed) {
        return closed ? pointsPriceReopenClosed : pointsPriceReopen;
    }

    /** What one more AI render on an already-rendered project costs, in paise. */
    public int renderTopUpPricePaise() {
        return renderTopUpPricePaise * (100 + Plan.GST_PERCENT) / 100;
    }

    // ── AI image credits ────────────────────────────────────────────────────

    /** What one AI image credit costs before the launch discount, in paise (GST included). */
    public int aiCreditListPricePaise() {
        return aiCreditListPricePaise * (100 + Plan.GST_PERCENT) / 100;
    }

    /** The launch discount currently on offer, as a whole percentage. 0 when it is over. */
    public int aiCreditDiscountPercent() {
        return clampPercent(aiCreditDiscountPercent);
    }

    /** What one AI image credit costs today, in paise (GST included). */
    public int aiCreditPricePaise() {
        return aiCreditPricePaise(1);
    }

    /**
     * What {@code credits} AI image credits cost today, in paise (GST included).
     *
     * The discount is applied to the WHOLE order rather than per credit and then multiplied,
     * so a rate that does not divide evenly cannot lose a rupee per credit to integer
     * truncation — at ten credits that is the difference between the price quoted on the
     * button and the price the order was created at, and verification refuses on exactly
     * that mismatch.
     *
     * <p>Both the order and the verify side call this, or a correctly-paid purchase fails
     * signature verification on an amount mismatch.
     */
    public int aiCreditPricePaise(int credits) {
        long gross = (long) aiCreditListPricePaise() * Math.max(0, credits);
        return (int) (gross * (100 - aiCreditDiscountPercent()) / 100);
    }

    public int aiCreditMinPurchase() {
        return Math.max(1, aiCreditMinPurchase);
    }

    public int aiCreditMaxPurchase() {
        return Math.max(aiCreditMinPurchase(), aiCreditMaxPurchase);
    }

    /** Credits one AI image costs. */
    public int aiCreditRenderCost() {
        return Math.max(1, aiCreditRenderCost);
    }

    /** A misconfigured discount must never make an order free, or negative. */
    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    public int pointsValidityDays() {
        return pointsValidityDays;
    }

    public int pointsExpiryWarningDays() {
        return pointsExpiryWarningDays;
    }

    /**
     * The account a shop is billed through, and the one its reward points land in. A shop
     * is billed through its OWNER everywhere else in the product, so points follow the
     * same account rather than inventing a second notion of "the shop's money".
     */
    public java.util.Optional<String> shopOwnerUserId(String orgId) {
        return membershipRepository
                .findUserIdsByOrganizationIdAndRole(
                        orgId, com.gridstore.huevista.account.model.OrgMemberRole.OWNER)
                .stream().findFirst();
    }

    /**
     * Is this shop covered by a plan? Asked of the owner account, per
     * {@link #shopOwnerUserId}. A shop with no owner account reads as unsubscribed — the
     * safe direction, since the alternative is giving away a subscribed rate to an org
     * nobody can be billed for.
     */
    public boolean isShopSubscribed(String orgId) {
        return shopOwnerUserId(orgId).map(this::isSubscribed).orElse(false);
    }

    public String currency() {
        return currency;
    }
}
