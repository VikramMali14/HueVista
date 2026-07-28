package com.gridstore.huevista.billing.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One place that answers "what does this cost?".
 *
 * There are exactly two prices denominated in money: a monthly plan, and buying points.
 * Everything else a shop can spend on — extra images, auto-masks, projects, reopens —
 * is priced in POINTS and nothing else. There is no prepaid rupee wallet and no
 * per-item cash checkout, so a purchase has one price rather than a cash price and a
 * points price that can drift apart.
 *
 * Cash amounts are in paise (Rs. 1 = 100 paise) to match Razorpay; point prices are in
 * whole points.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final BillingService billingService;
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
    @Value("${app.store.price-paise:9900}")
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

    @Value("${app.points.image:40}")
    private int pointsPriceImage;

    @Value("${app.points.auto-mask:20}")
    private int pointsPriceAutoMask;

    @Value("${app.points.project:80}")
    private int pointsPriceProject;

    @Value("${app.points.reopen:9}")
    private int pointsPriceReopen;

    @Value("${app.points.validity-days:365}")
    private int pointsValidityDays;

    @Value("${app.points.expiry-warning-days:10}")
    private int pointsExpiryWarningDays;

    @Value("${app.project-credit.currency:INR}")
    private String currency;

    public boolean isSubscribed(String userId) {
        return billingService.findEntitlingSubscription(userId).isPresent();
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

    public int pointsPriceImage() {
        return pointsPriceImage;
    }

    public int pointsPriceAutoMask() {
        return pointsPriceAutoMask;
    }

    public int pointsPriceProject() {
        return pointsPriceProject;
    }

    public int pointsPriceReopen() {
        return pointsPriceReopen;
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
