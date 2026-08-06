package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The free tier's billing cycle — the one plan nothing at the gateway renews.
 *
 * A paid plan's month is rolled over by Razorpay: {@code payment.captured} arrives, the
 * usage counters reset and the period moves on (see
 * {@link BillingService#handlePaymentCaptured}). The free tier has no Razorpay
 * subscription behind it — there is nothing to charge — so if nothing else moved it, a
 * free shop would spend its two projects in the first month and then sit at a permanently
 * full counter, which is the seven-day trial's dead end wearing a different name.
 *
 * This is the missing renewal. It rolls a lapsed free cycle into the next month with the
 * allowance reset, on the same terms a paid renewal does: what the shop BOUGHT outright
 * survives (purchased credits, the holds standing behind access codes it has issued), and
 * what was merely lent for a month does not.
 *
 * <p>Rolled from two directions, deliberately:
 * <ul>
 *   <li>{@link #renewIfDue(String)} on the paths that read or spend the allowance, so a
 *       shop that comes back on the 1st gets its new projects on the spot rather than
 *       waiting for a job to notice;</li>
 *   <li>the daily sweep in {@link BillingService#expireStaleSubscriptions()}, so the cycle
 *       still turns over for an account nobody signed into that month.</li>
 * </ul>
 * Both land on {@link #renew(Subscription)}, and it is idempotent in the sense that
 * matters: a row whose period has not elapsed is left alone, so a double call cannot hand
 * out two months of allowance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FreeTierService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;

    /**
     * Bring {@code userId}'s free tier up to date before their allowance is read or spent:
     * roll a lapsed free month into the next one, and give the free tier back to a shop
     * that has ended up with no plan at all.
     *
     * <p>The second half is what makes this a TIER rather than a welcome gift. A shop that
     * subscribed and later cancelled, or whose card stopped working, used to land on
     * nothing — strictly worse than a shop that never paid a rupee, which is a poor thing
     * to do to a former customer and a poor argument for subscribing in the first place.
     * The free plan is the floor now, so lapsing lands on it.
     *
     * <p>REQUIRES_NEW because the callers are the quota gates, and several of them run
     * inside a read-only transaction (the plan page) or inside the transaction that a
     * project creation is being charged against. Neither can carry this write: the first
     * would fail on it outright, and the second would tie a renewal that belongs to the
     * calendar to whether one particular project happened to be created successfully.
     *
     * <p>Cheap when there is nothing to do, which is almost always: one indexed lookup that
     * comes back empty for every shop not standing on a month boundary.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureCurrentCycle(String userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Subscription> due = subscriptionRepository
                .findDueFreeCycles(userId, Plan.FREE, SubscriptionStatus.ACTIVE, now);
        if (!due.isEmpty()) {
            due.forEach(this::renew);
            return;
        }
        restoreIfLapsed(userId, now);
    }

    /**
     * Put a shop with nothing left back on the free tier.
     *
     * Scoped to RETAILER accounts because that is who plans are for: a customer works
     * through the code their shop gave them and a painter through the shop above them, so
     * minting a subscription row for either would be inventing an entitlement neither
     * asked for and neither reads.
     *
     * Does nothing while anything still entitles — including a cancelled plan running out
     * the days it was paid for. Handing the free tier to a shop mid-way through a Business
     * month would put a 2-project row in front of a 100-project one, since the entitlement
     * lookup prefers the newest ACTIVE row.
     */
    private void restoreIfLapsed(String userId, LocalDateTime now) {
        boolean entitled = !subscriptionRepository.findEntitling(
                userId, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED, now).isEmpty();
        if (entitled) {
            return;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getRole() != UserRole.RETAILER) {
            return;
        }
        Subscription free = Subscription.builder()
                .user(user)
                .plan(Plan.FREE)
                .status(SubscriptionStatus.ACTIVE)
                .trial(false)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plusMonths(1))
                .projectsUsed(0)
                .projectsLimit(Plan.FREE.getMonthlyProjectLimit())
                .pdfDownloadsLimit(Plan.FREE.getMonthlyPdfLimit())
                .pdfImageLimit(Plan.FREE.getPdfImageLimit())
                .build();
        // The SAVED instance, not the one handed in: the id is generated during the write,
        // and the sweep below keys every carry-over on it.
        Subscription saved = subscriptionRepository.save(free);
        reclaimStrandedCredits(userId, saved.getId());
        log.info("Free tier restored after a lapsed plan: user={} subId={}", userId, saved.getId());
    }

    /**
     * Move what the shop still OWNS off the dead plan and onto the free row now in force:
     * extra projects it paid for outright, and the holds standing behind access codes its
     * customers have not redeemed yet.
     *
     * The same sweep {@code BillingService#reclaimStrandedCredits} does when a paid plan
     * goes live, for the same reason and with the same rule about what qualifies — a
     * purchased credit is money and never evaporates, a hold belongs to the code that owns
     * it, and the dead plan's monthly allowance genuinely did expire with it. Landing on
     * the free tier is the other way an account acquires a new current subscription, so it
     * needs the sweep just as much: without it a shop that bought three extra projects and
     * let its plan lapse would find them on a row nothing can read, and a customer
     * redeeming an outstanding code would be charged for it a second time.
     *
     * Moving rather than copying is what keeps it safe to run repeatedly: a swept row is
     * left at zero, so a second pass finds nothing.
     */
    private void reclaimStrandedCredits(String userId, String keepSubId) {
        for (Subscription stranded : subscriptionRepository.findWithUnspentCredits(userId, keepSubId)) {
            int purchased = stranded.getPurchasedProjectCredits();
            int held = stranded.getReservedProjects();
            if (purchased > 0) {
                subscriptionRepository.addPurchasedProjectCredits(keepSubId, purchased);
                stranded.setPurchasedProjectCredits(0);
            }
            if (held > 0) {
                subscriptionRepository.addReservedProjects(keepSubId, held);
                stranded.setReservedProjects(0);
            }
            subscriptionRepository.save(stranded);
            log.info("Reclaimed stranded credits onto the free tier {}: purchased={} held={}",
                    keepSubId, purchased, held);
        }
    }

    /**
     * Give {@code sub} a fresh month: the allowance rebuilt from the plan, this cycle's
     * usage zeroed, and the period moved on from now.
     *
     * <p>The period restarts at {@code now} rather than at the old {@code currentPeriodEnd}
     * on purpose. An account dormant for six months would otherwise need six rolls to catch
     * up — and would come back to a cycle that expired months ago — so the month a shop
     * gets is the month from the day it returns.
     *
     * <p>What moves and what does not follows the paid renewal exactly, because these are
     * the same two kinds of credit:
     * <ul>
     *   <li>{@code purchasedProjectCredits} survive — a shop that paid ₹99 for an extra
     *       project owns it, and a free month turning over is not a reason to take it;</li>
     *   <li>{@code reservedProjects} survive — a code issued last month is still redeemable
     *       this one, and zeroing the hold would give that project away twice;</li>
     *   <li>{@code carriedProjectCredits} expire — they were someone's unused monthly
     *       allowance, lent for one cycle;</li>
     *   <li>{@code trialProjectsCreated} is reset, so a row that was a seven-day trial
     *       before the free tier existed is not held to that spent counter forever.</li>
     * </ul>
     */
    @Transactional
    public void renew(Subscription sub) {
        LocalDateTime now = LocalDateTime.now();
        if (sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isAfter(now)) {
            return; // still inside the month it already has
        }
        Plan plan = sub.getPlan();
        sub.setProjectsUsed(0);
        sub.setPdfDownloadsUsed(0);
        sub.setCarriedProjectCredits(0);
        sub.setTrialProjectsCreated(0);
        // Rebuilt from the plan rather than only ever raised, so a change to what the free
        // tier includes reaches the shops already on it instead of only new signups.
        sub.setProjectsLimit(plan.getMonthlyProjectLimit());
        sub.setPdfDownloadsLimit(plan.getMonthlyPdfLimit());
        sub.setPdfImageLimit(plan.getPdfImageLimit());
        sub.setCurrentPeriodStart(now);
        sub.setCurrentPeriodEnd(now.plusMonths(1));
        subscriptionRepository.save(sub);
        log.info("Free tier renewed: user={} subId={} projects={} until={}",
                sub.getUser().getId(), sub.getId(), sub.getProjectsLimit(), sub.getCurrentPeriodEnd());
    }
}
