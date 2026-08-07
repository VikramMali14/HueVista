package com.gridstore.huevista.account.service;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

/**
 * Which pages a shop's own PLAN withholds — the second answer to "may this shop open this
 * page?", beside {@link FeatureAccessService}.
 *
 * The two limits look alike and are deliberately kept apart, because they are asked of
 * different people and are fixed in different ways. A distributor grant is another
 * business's decision about this shop, and the way out of it is to ring the distributor.
 * A plan limit is the shop's own decision about its own money, and the way out is the
 * subscribe button. Folding the second into the first would have told a free shop to "ask
 * your distributor to enable it" about something no distributor can enable.
 *
 * <p>Only the free tier withholds anything today: colour matching (the Colour finder). It
 * is the one tool in the product that is worth money without a project behind it — a
 * counter can answer "what shade is this wall?" all day and never spend the allowance the
 * tiers are priced on — so it is the line between the free plan and the paid ones.
 *
 * <p>Applies to RETAILER accounts only, exactly like the distributor grant. An admin, a
 * distributor or a painter holds no shop plan, and reading them as "no plan, therefore the
 * free tier's limits" would lock the platform's own operators out of a page nobody
 * intended to restrict.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanFeatureService {

    private final BillingService billingService;
    private final UserRepository userRepository;

    /**
     * The pages {@code plan} does not include.
     *
     * Keyed off the capability flags on {@link Plan} rather than a list of tier names
     * here, so a tier that changes what it includes changes this with it.
     */
    public Set<AppFeature> withheldBy(Plan plan) {
        Set<AppFeature> withheld = EnumSet.noneOf(AppFeature.class);
        if (!plan.isColorMatching()) {
            withheld.add(AppFeature.COLOR_FINDER);
        }
        return withheld;
    }

    /**
     * The pages this user's plan withholds — empty for anyone the limit does not apply to.
     *
     * A shop with no live plan at all reads as the free tier, which is both the truthful
     * answer (that is the tier it will land back on) and the safe direction: the
     * alternative is handing a lapsed account the tools of a plan it no longer holds.
     */
    @Transactional(readOnly = true)
    public Set<AppFeature> withheldFor(String userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getRole() != UserRole.RETAILER) {
            return Set.of();
        }
        return withheldBy(planOf(userId));
    }

    /** The tier a shop's page access is read against — its live plan, else the free tier. */
    @Transactional(readOnly = true)
    public Plan planOf(String userId) {
        return billingService.findEntitlingSubscription(userId)
                .map(Subscription::getPlan)
                .orElse(Plan.FREE);
    }

    /** True when this user's plan includes {@code feature}. */
    @Transactional(readOnly = true)
    public boolean includes(String userId, AppFeature feature) {
        return !withheldFor(userId).contains(feature);
    }

    /**
     * Guard for an endpoint behind a tier-gated page. Throws {@link SecurityException} —
     * which the app's handler renders as 403 — naming the page AND the way out, since
     * unlike a distributor grant this is a limit the shop can lift itself.
     */
    @Transactional(readOnly = true)
    public void assertIncluded(String userId, AppFeature feature) {
        if (!includes(userId, feature)) {
            throw new SecurityException("\"" + feature.getLabel() + "\" isn't part of the free plan. "
                    + "Subscribe to any paid plan to switch it on.");
        }
    }
}
