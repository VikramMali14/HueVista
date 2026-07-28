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
     * The platform's cut of every kiosk order — flat, whatever the shop's plan is doing.
     *
     * The kiosk is the counter, not the subscription: a walk-in pays the shop's printed
     * price, the platform keeps this base, and the excess accrues to the shop's wallet.
     * Tying it to subscription state was tried and taken back out — it silently changed
     * the price on a printed URL and wiped the shop's margin on a payment they had already
     * advertised, which is not a lever that belongs on a public payment page.
     */
    @Value("${app.store.min-price-paise:5000}")
    private int kioskBasePaise;

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

    /** The platform's cut of one kiosk order. Flat, whatever the shop's plan is doing. */
    public int kioskBasePaise() {
        return kioskBasePaise;
    }

    /**
     * Is this shop covered by a plan? A shop is billed through its OWNER account
     * everywhere else in the product, so that is what is asked here too. A shop with no
     * owner account reads as unsubscribed — the safe direction, since the alternative is
     * giving away a subscribed rate to an org nobody can be billed for.
     */
    public boolean isShopSubscribed(String orgId) {
        return membershipRepository
                .findUserIdsByOrganizationIdAndRole(
                        orgId, com.gridstore.huevista.account.model.OrgMemberRole.OWNER)
                .stream().findFirst()
                .map(this::isSubscribed)
                .orElse(false);
    }

    public String currency() {
        return currency;
    }
}
