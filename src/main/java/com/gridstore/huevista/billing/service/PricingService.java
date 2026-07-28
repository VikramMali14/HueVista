package com.gridstore.huevista.billing.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One place that answers "what does this cost, for this account, right now?".
 *
 * Every price in the product now has two faces — one for an account with a live
 * subscription and one for an account without — and the second is always the one a
 * lapsed shop hits. Scattering that pair across the kiosk, the project-credit checkout
 * and the retailer panel is how the three drift apart and the customer is quoted one
 * number and charged another, so they all read from here.
 *
 * Prices are in paise (Rs. 1 = 100 paise) to match Razorpay.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final BillingService billingService;
    private final com.gridstore.huevista.account.repository.OrgMembershipRepository membershipRepository;

    /** One extra project, bought while the account holds a live subscription. */
    @Value("${app.project-credit.subscribed-paise:5000}")
    private int projectSubscribedPaise;

    /** One project, bought with no subscription — the standalone price. */
    @Value("${app.project-credit.unsubscribed-paise:9900}")
    private int projectUnsubscribedPaise;

    /** Reopening a project whose paid window has run out. */
    @Value("${app.project-credit.reopen-paise:900}")
    private int projectReopenPaise;

    /** Days of access a purchased project (or a reopen) opens. */
    @Value("${app.project-credit.valid-days:30}")
    private int projectValidDays;

    /**
     * What a walk-in pays at any kiosk — one flat price across the platform, whatever the
     * shop's plan is doing and whatever shop it is.
     *
     * The shop does not set this and takes no share of it: the walk-in is HueVista's own
     * customer, so the whole amount is ours for our own service. The shop is rewarded in
     * closed-loop points instead ({@link #kioskBonusPointsPaise()}). Letting the shop
     * price the link and keep the excess is what made this a third-party collection, and
     * that is the thing this design removes.
     */
    @Value("${app.store.price-paise:9900}")
    private int kioskPricePaise;

    /**
     * Points awarded to the shop whose link made the sale. One point = one paise of
     * spending power inside HueVista, never withdrawable.
     */
    @Value("${app.store.bonus-points-paise:3900}")
    private int kioskBonusPointsPaise;

    @Value("${app.project-credit.currency:INR}")
    private String currency;

    public boolean isSubscribed(String userId) {
        return billingService.findEntitlingSubscription(userId).isPresent();
    }

    /** What one more project costs {@code userId} today. */
    public int projectPricePaise(String userId) {
        return projectPricePaise(isSubscribed(userId));
    }

    public int projectPricePaise(boolean subscribed) {
        return subscribed ? projectSubscribedPaise : projectUnsubscribedPaise;
    }

    public int projectSubscribedPricePaise() {
        return projectSubscribedPaise;
    }

    public int projectUnsubscribedPricePaise() {
        return projectUnsubscribedPaise;
    }

    public int projectReopenPricePaise() {
        return projectReopenPaise;
    }

    public int projectValidDays() {
        return projectValidDays;
    }

    /** What one kiosk visualisation costs a walk-in. Flat across the platform. */
    public int kioskPricePaise() {
        return kioskPricePaise;
    }

    /** Points the shop earns per kiosk sale (1 point = 1 paise of spending power). */
    public int kioskBonusPointsPaise() {
        return kioskBonusPointsPaise;
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
