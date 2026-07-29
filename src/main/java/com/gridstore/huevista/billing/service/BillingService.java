package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.CreateSubscriptionRequest;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifySubscriptionRequest;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionPayment;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionPaymentRepository;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;
    private final UserRepository userRepository;
    private final RazorpayClient razorpayClient;
    private final com.gridstore.huevista.common.audit.AuditService auditService;
    private final BillingEmailService billingEmailService;

    // Read straight from configuration rather than through PricingService: that class
    // depends on THIS one to answer "is this account subscribed", so injecting it back
    // would close a cycle. These two are only used to phrase a quota message.
    @Value("${app.points.image:40}")
    private int pointsPriceImage;

    @Value("${app.points.auto-mask:20}")
    private int pointsPriceAutoMask;

    @Value("${razorpay.plan.starter:}")
    private String planIdStarter;

    @Value("${razorpay.plan.professional:}")
    private String planIdProfessional;

    @Value("${razorpay.plan.business:}")
    private String planIdBusiness;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    // Number of monthly billing cycles Razorpay runs before it auto-completes the
    // subscription. Defaults to 120 (10 years) so an ongoing monthly plan does not
    // silently stop after a year; lower it only if you truly want a fixed-term plan.
    @Value("${razorpay.subscription.total-count:120}")
    private int subscriptionTotalCount;

    // Grace window before the daily safety-net job hard-expires a PAID subscription whose
    // period has lapsed. Renewals are driven by webhooks, which can be delayed; without a
    // grace period a late webhook would cut off a customer who has actually paid.
    private static final int RENEWAL_GRACE_DAYS = 3;

    /**
     * Statuses a subscription never comes back from. Reaching one of these means the
     * gateway will not charge this subscription again, so nothing may re-activate it in
     * place — a new one has to be bought.
     */
    private static final java.util.Set<SubscriptionStatus> TERMINAL_STATUSES = java.util.EnumSet.of(
            SubscriptionStatus.EXPIRED, SubscriptionStatus.CANCELLED, SubscriptionStatus.COMPLETED);

    @Transactional
    public SubscriptionResponse createSubscription(String userId, CreateSubscriptionRequest request) {
        if (request.getPlan() == Plan.ENTERPRISE) {
            throw new IllegalArgumentException("Enterprise plans require manual setup. Please contact sales.");
        }
        // The free tier is granted, never sold — there is nothing to charge for, and
        // letting it through would create a Razorpay subscription for Rs. 0.
        if (request.getPlan().isFree()) {
            throw new IllegalArgumentException(
                    "The free trial is included with your account — there's nothing to pay. "
                    + "Choose a paid plan to keep going once it ends.");
        }

        // An active free trial never blocks buying a plan (the trial is superseded once
        // the plan activates). An active PAID subscription allows exactly one in-place
        // change: an UPGRADE to a higher tier — the old plan is cancelled and replaced
        // the moment the new one activates (see verifyAndActivateSubscription /
        // activateSubscription). Same tier or a downgrade still requires cancelling
        // first, so nobody accidentally double-pays for a sideways move.
        // A plan already scheduled to end does NOT block a new purchase — that was the
        // cancellation trap: after cancelling, the still-ACTIVE row rejected both
        // re-subscribing to the same plan and moving to a smaller one, so the only way
        // back was to wait for the period to run out.
        Subscription activePaid = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .filter(s -> !s.isTrial())
                .filter(s -> !s.isCancelAtPeriodEnd())
                .orElse(null);
        if (activePaid != null) {
            if (request.getPlan() == activePaid.getPlan()) {
                throw new IllegalStateException(
                        "You're already on the " + activePaid.getPlan().getDisplayName() + " plan.");
            }
            if (!request.getPlan().isUpgradeFrom(activePaid.getPlan())) {
                throw new IllegalStateException(
                        "To move to a smaller plan, cancel your current one first — it stays "
                        + "active till the end of the period, and you can subscribe to "
                        + request.getPlan().getDisplayName() + " after that.");
            }
            log.info("Upgrade requested: user={} {} -> {}", userId, activePaid.getPlan(), request.getPlan());
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Plans are retailer (shop) products. A CUSTOMER paying for one would get
        // nothing — their project access is governed by the retailer-issued access
        // code entitlement, which a subscription doesn't touch. Refuse the charge
        // instead of taking money that unlocks nothing.
        if (user.getRole() == com.gridstore.huevista.auth.model.UserRole.CUSTOMER) {
            throw new SecurityException(
                    "Subscription plans are for paint shops. If you're visualising your own room, "
                    + "redeem an access code from your paint shop instead.");
        }

        String razorpayPlanId = resolveRazorpayPlanId(request.getPlan());
        if (razorpayPlanId.isBlank()) {
            throw new IllegalStateException("Razorpay plan ID not configured for: " + request.getPlan());
        }

        // Hand back the attempt already waiting for this exact plan rather than opening a
        // second one, and retire the attempts for plans the shop moved on from.
        SubscriptionResponse pending = reusePendingAttempt(userId, request);
        if (pending != null) {
            return pending;
        }

        // A plan already winding down keeps every day it was paid for, so the replacement
        // starts the day that period ends. Billing it immediately took a second month's
        // money AND threw the remaining days away when supersedeActiveSubscriptions
        // expired the old row on activation.
        LocalDateTime scheduledStart = scheduledStartFor(userId);

        try {
            JSONObject subRequest = new JSONObject();
            subRequest.put("plan_id", razorpayPlanId);
            subRequest.put("total_count", subscriptionTotalCount);
            subRequest.put("quantity", request.getQuantity());
            subRequest.put("customer_notify", 1);
            if (scheduledStart != null) {
                subRequest.put("start_at",
                        scheduledStart.atZone(java.time.ZoneId.systemDefault()).toEpochSecond());
            }

            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("plan", request.getPlan().name());
            subRequest.put("notes", notes);

            com.razorpay.Subscription rzpSub = razorpayClient.subscriptions.create(subRequest);
            String rzpSubId = rzpSub.get("id");
            String paymentUrl = rzpSub.has("short_url") ? rzpSub.get("short_url") : null;

            Subscription sub = Subscription.builder()
                    .user(user)
                    .plan(request.getPlan())
                    .status(SubscriptionStatus.CREATED)
                    .razorpaySubscriptionId(rzpSubId)
                    // A scheduled plan carries its start date from the moment it exists, so
                    // findEntitling can tell "bought, starts later" from "in force now".
                    .currentPeriodStart(scheduledStart)
                    .currentPeriodEnd(scheduledStart != null ? scheduledStart.plusMonths(1) : null)
                    // quantity multiplies the amount Razorpay bills, so scale the image and
                    // auto-mask quotas by it too — otherwise a customer paying Nx would still
                    // get a single plan's limit.
                    .aiGenerationsLimit(scaledLimit(request.getPlan().getMonthlyImageLimit(), request.getQuantity()))
                    .autoMasksLimit(scaledLimit(request.getPlan().getMonthlyAutoMaskLimit(), request.getQuantity()))
                    // PDF downloads scale with quantity like the image quota; images-per-PDF is a
                    // per-document property of the tier, so it does NOT scale.
                    .pdfDownloadsLimit(scaledLimit(request.getPlan().getMonthlyPdfLimit(), request.getQuantity()))
                    .pdfImageLimit(request.getPlan().getPdfImageLimit())
                    .build();

            subscriptionRepository.save(sub);
            log.info("Subscription created: user={} plan={} rzpId={} startsAt={}",
                    userId, request.getPlan(), rzpSubId, scheduledStart);
            // razorpayKeyId lets the browser open the in-app Checkout for this subscription;
            // paymentUrl (hosted short_url) is kept as a fallback for clients that can't.
            return SubscriptionResponse.from(sub, paymentUrl, keyId);

        } catch (RazorpayException e) {
            log.error("Razorpay subscription creation failed: {}", e.getMessage());
            throw new IllegalStateException("Payment gateway error: " + e.getMessage());
        }
    }

    /**
     * When the caller already holds a PAID plan that is winding down, the moment its paid
     * period ends — otherwise null (start now).
     *
     * Only a paid plan defers: a free trial costs nothing to walk away from, and making a
     * shop wait out its trial before the plan they just bought begins would be worse for
     * them, not better.
     */
    private LocalDateTime scheduledStartFor(String userId) {
        LocalDateTime now = LocalDateTime.now();
        return findEntitlingSubscription(userId)
                .filter(s -> !s.isTrial())
                .filter(Subscription::isCancelAtPeriodEnd)
                .map(Subscription::getCurrentPeriodEnd)
                // Razorpay rejects a start_at that isn't comfortably in the future, and a
                // plan ending within the hour isn't worth deferring for anyway.
                .filter(end -> end != null && end.isAfter(now.plusHours(1)))
                .orElse(null);
    }

    /**
     * Reuse the checkout attempt already open for this exact plan, and retire the rest.
     *
     * Every click on a plan card used to create a fresh Razorpay subscription, and with
     * {@code customer_notify} on, its own live "pay now" link. Browsing three plans left
     * three payable links for three different plans, so a customer could later pay one
     * they had not chosen. Returns the response to hand back when an attempt can be
     * reused, or null when a new subscription should be created.
     */
    private SubscriptionResponse reusePendingAttempt(String userId, CreateSubscriptionRequest request) {
        int wantedImages = scaledLimit(request.getPlan().getMonthlyImageLimit(), request.getQuantity());
        SubscriptionResponse reusable = null;

        for (Subscription attempt : subscriptionRepository
                .findByUserIdAndStatus(userId, SubscriptionStatus.CREATED)) {
            String rzpId = attempt.getRazorpaySubscriptionId();
            boolean sameOffer = attempt.getPlan() == request.getPlan()
                    && attempt.getAiGenerationsLimit() == wantedImages;

            if (sameOffer && reusable == null && rzpId != null && !rzpId.isBlank()) {
                String shortUrl = stillPayableShortUrl(rzpId);
                if (shortUrl != null) {
                    log.info("Reusing pending checkout attempt: user={} plan={} rzpId={}",
                            userId, request.getPlan(), rzpId);
                    reusable = SubscriptionResponse.from(
                            attempt, shortUrl.isBlank() ? null : shortUrl, keyId);
                    continue;
                }
            }
            retirePendingAttempt(attempt);
        }
        return reusable;
    }

    /**
     * The hosted payment link of a gateway subscription still awaiting authorization
     * ({@code ""} when it has none), or null when it is gone, already paid, or the
     * gateway can't be reached — in which case the caller creates a fresh one rather
     * than pointing the buyer at a link that may not work.
     */
    private String stillPayableShortUrl(String razorpaySubscriptionId) {
        try {
            com.razorpay.Subscription fetched = razorpayClient.subscriptions.fetch(razorpaySubscriptionId);
            String status = fetched.has("status") ? fetched.get("status") : "";
            if (!"created".equalsIgnoreCase(status)) {
                return null;
            }
            String shortUrl = fetched.has("short_url") ? fetched.get("short_url") : null;
            return shortUrl == null ? "" : shortUrl;
        } catch (Exception e) {
            log.warn("Could not re-check pending subscription {} — creating a new one: {}",
                    razorpaySubscriptionId, e.getMessage());
            return null;
        }
    }

    /** Take an abandoned attempt out of play on both sides so its payment link dies with it. */
    private void retirePendingAttempt(Subscription attempt) {
        String rzpId = attempt.getRazorpaySubscriptionId();
        if (rzpId != null && !rzpId.isBlank()) {
            try {
                JSONObject cancelRequest = new JSONObject();
                cancelRequest.put("cancel_at_cycle_end", 0);
                razorpayClient.subscriptions.cancel(rzpId, cancelRequest);
            } catch (Exception e) {
                log.warn("Could not cancel abandoned checkout attempt {} (expiring locally anyway): {}",
                        rzpId, e.getMessage());
            }
        }
        expireLocally(attempt);
        log.info("Abandoned checkout attempt retired: subId={} plan={}",
                attempt.getId(), attempt.getPlan());
    }

    /**
     * Verify the Razorpay Checkout success payload and activate the subscription
     * synchronously, so the retailer has an ACTIVE plan the moment they return from
     * payment instead of waiting on the {@code subscription.activated} webhook (which
     * may be delayed or, in a not-yet-configured environment, never arrive).
     *
     * The {@code subscription.charged}/{@code payment.captured} webhook remains the
     * source of truth for renewals; this only fast-paths the first activation. Safe to
     * call more than once — an already-active subscription is returned unchanged.
     */
    @Transactional
    public SubscriptionResponse verifyAndActivateSubscription(String userId, VerifySubscriptionRequest request) {
        // HMAC-SHA256 over "<payment_id>|<subscription_id>" must equal the signature
        // Razorpay handed the browser. This proves the payment really came from Razorpay.
        String payload = request.getPaymentId() + "|" + request.getSubscriptionId();
        try {
            if (!com.razorpay.Utils.verifySignature(payload, request.getSignature(), keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay subscription signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        Subscription sub = subscriptionRepository.findByRazorpaySubscriptionId(request.getSubscriptionId())
                .orElseThrow(() -> new ResourceNotFoundException("Subscription not found"));

        // The signature only proves the payment is genuine — bind it to the caller so a
        // payment for someone else's subscription can't activate this account.
        if (!userId.equals(sub.getUser().getId())) {
            log.warn("Subscription verify ownership mismatch: user={} subId={} owner={}",
                    userId, request.getSubscriptionId(), sub.getUser().getId());
            throw new SecurityException("Payment verification failed.");
        }

        if (sub.getStatus() == SubscriptionStatus.ACTIVE) {
            return SubscriptionResponse.from(sub); // already activated; nothing to do
        }

        // A subscription that has ENDED never comes back through this door. The signature
        // is a plain HMAC over "<payment>|<subscription>" — no nonce, no expiry — so the
        // payload from a shop's very first successful checkout stays valid forever. With
        // only an "is it already ACTIVE?" guard, re-POSTing it each time the plan lapsed
        // renewed it free of charge; replaying a superseded plan's payload also expired
        // and gateway-cancelled the bigger one that had replaced it. Pay again instead.
        if (TERMINAL_STATUSES.contains(sub.getStatus())) {
            log.warn("Rejected verify against a {} subscription: user={} rzpId={}",
                    sub.getStatus(), userId, request.getSubscriptionId());
            throw new IllegalStateException(
                    "That plan has already ended and can't be restarted from an old payment. "
                    + "Choose a plan to subscribe again.");
        }

        // Claim the payment. Belt to the status guard's braces: one payment authorizes one
        // activation, and the primary key settles a race between two concurrent submits.
        claimSubscriptionPayment(request.getPaymentId(), request.getSubscriptionId(), sub, userId);

        LocalDateTime now = LocalDateTime.now();
        // A plan bought to replace one that is still winding down was scheduled at the
        // gateway to begin when that period ends; authorizing it must not drag it forward.
        boolean scheduled = sub.getCurrentPeriodStart() != null
                && sub.getCurrentPeriodStart().isAfter(now);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setTrial(false);
        if (!scheduled) {
            sub.setCurrentPeriodStart(now);
            // Approximate the first monthly period; the renewal webhook corrects it later.
            sub.setCurrentPeriodEnd(now.plusMonths(1));
        }
        subscriptionRepository.save(sub);
        if (!scheduled) {
            // The retailer just paid — end any other still-active subscription (a free
            // trial, or the smaller plan this purchase upgrades from) so they never hold
            // two active plans or get double-billed. A SCHEDULED plan supersedes nothing:
            // the plan it replaces is already set to stop on the day this one starts.
            supersedeActiveSubscriptions(sub.getUser().getId(), sub.getId());
        }
        auditService.record(userId, "SUBSCRIPTION_ACTIVATE", "SUBSCRIPTION", sub.getId(),
                "plan=" + sub.getPlan() + " rzpId=" + request.getSubscriptionId()
                        + (scheduled ? " startsAt=" + sub.getCurrentPeriodStart() : ""));
        log.info("Subscription activated via checkout verify: user={} subId={} scheduled={}",
                userId, sub.getId(), scheduled);
        billingEmailService.sendSubscriptionActivated(sub);
        return SubscriptionResponse.from(sub);
    }

    /**
     * Record {@code paymentId} as spent, refusing a payment that already activated
     * something. The pre-check keeps the common case readable; the primary key is the
     * race-safe backstop for two concurrent submits of the same payload.
     */
    private void claimSubscriptionPayment(String paymentId, String razorpaySubscriptionId,
                                          Subscription sub, String userId) {
        if (subscriptionPaymentRepository.existsById(paymentId)) {
            log.warn("Replayed subscription payment rejected: user={} paymentId={}", userId, paymentId);
            throw new IllegalStateException("This payment has already been used to start a plan.");
        }
        try {
            subscriptionPaymentRepository.saveAndFlush(SubscriptionPayment.of(
                    paymentId, razorpaySubscriptionId, sub.getId(), userId));
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been used to start a plan.");
        }
    }

    /**
     * Grant a free trial subscription (no Razorpay) so a newly-signed-up retailer can
     * use AI features immediately. Status is ACTIVE with a {@code trialDays} window;
     * the daily {@link #expireStaleSubscriptions()} job flips it to EXPIRED at the end.
     * Idempotent — no-op (returns the existing one) if the user already has an active sub.
     */
    @Transactional
    public SubscriptionResponse grantTrial(String userId, Plan plan, int trialDays) {
        // "Already has a live plan" means entitled, not merely ACTIVE. A shop that
        // cancelled a paid plan still has every day it paid for; handing it the free tier
        // on top put a 3-image row in front of a Business one (findEntitling prefers
        // ACTIVE), silently downgrading a paying customer through the distributor's
        // "grant free tier" button.
        if (findEntitlingSubscription(userId).isPresent()) {
            return getCurrentSubscription(userId);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        // A trial on a PAID tier handed out a month of Professional quota for nothing and
        // made the subscribe decision feel like a downgrade. The free tier is the trial.
        Plan p = (plan == null || plan == Plan.ENTERPRISE) ? Plan.FREE : plan;
        LocalDateTime now = LocalDateTime.now();
        Subscription sub = Subscription.builder()
                .user(user)
                .plan(p)
                .status(SubscriptionStatus.ACTIVE)
                .trial(true)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plusDays(trialDays))
                .aiGenerationsUsed(0)
                .aiGenerationsLimit(p.getMonthlyImageLimit())
                .autoMasksLimit(p.getMonthlyAutoMaskLimit())
                .pdfDownloadsLimit(p.getMonthlyPdfLimit())
                .pdfImageLimit(p.getPdfImageLimit())
                .build();
        subscriptionRepository.save(sub);
        log.info("Trial granted: user={} plan={} days={}", userId, p, trialDays);
        return SubscriptionResponse.from(sub);
    }

    /**
     * ADMIN: activate a subscription for {@code targetUserId} without a payment —
     * used to comp a shop, onboard offline-paying customers, or restore access.
     * Any currently ACTIVE subscription (paid or trial) is expired first so the
     * user never holds two active plans; the granted one has no Razorpay id, so a
     * later self-serve cancel ends it locally (see {@link #cancelSubscription}).
     */
    @Transactional
    public SubscriptionResponse adminGrantSubscription(String adminUserId, String targetUserId,
                                                       Plan plan, int days, Integer aiLimitOverride) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + targetUserId));

        LocalDateTime now = LocalDateTime.now();
        Subscription sub = Subscription.builder()
                .user(user)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .trial(false)
                .currentPeriodStart(now)
                .currentPeriodEnd(now.plusDays(days))
                .aiGenerationsUsed(0)
                .aiGenerationsLimit(aiLimitOverride != null ? aiLimitOverride : plan.getMonthlyImageLimit())
                .autoMasksLimit(plan.getMonthlyAutoMaskLimit())
                .pdfDownloadsLimit(plan.getMonthlyPdfLimit())
                .pdfImageLimit(plan.getPdfImageLimit())
                .build();
        subscriptionRepository.save(sub);

        // Retire what they were on AFTER the replacement exists, so its purchased credits
        // and outstanding access-code holds have somewhere to land.
        subscriptionRepository.findByUserIdAndStatus(targetUserId, SubscriptionStatus.ACTIVE)
                .forEach(existing -> {
                    if (existing.getId().equals(sub.getId())) {
                        return;
                    }
                    carryOverCredits(existing, sub.getId());
                    expireLocally(existing);
                    log.info("Subscription superseded by admin grant: user={} subId={}",
                            targetUserId, existing.getId());
                });

        auditService.record(adminUserId, "ADMIN_SUBSCRIPTION_GRANT", "SUBSCRIPTION", sub.getId(),
                "user=" + targetUserId + " plan=" + plan + " days=" + days
                        + (aiLimitOverride != null ? " aiLimit=" + aiLimitOverride : ""));
        log.info("Admin {} granted subscription: user={} plan={} days={}", adminUserId, targetUserId, plan, days);
        return SubscriptionResponse.from(sub);
    }

    /**
     * ADMIN: adjust a user's most recent subscription in place — add AI generation
     * credits (raises the limit) and/or extend the period end. Extending a lapsed
     * (EXPIRED/CANCELLED/HALTED) subscription reactivates it, which is how an
     * ended plan is brought back without making the user pay again.
     */
    @Transactional
    public SubscriptionResponse adminAdjustSubscription(String adminUserId, String targetUserId,
                                                        Integer addAiGenerations, Integer extendDays) {
        if (addAiGenerations == null && extendDays == null) {
            throw new IllegalArgumentException(
                    "Nothing to adjust — provide addAiGenerations and/or extendDays.");
        }
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(targetUserId, SubscriptionStatus.ACTIVE)
                .orElseGet(() -> subscriptionRepository
                        .findByUserIdOrderByCreatedAtDesc(targetUserId)
                        .stream().findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "No subscription to adjust — grant one first.")));

        LocalDateTime now = LocalDateTime.now();
        if (addAiGenerations != null) {
            long raised = (long) sub.getAiGenerationsLimit() + addAiGenerations;
            sub.setAiGenerationsLimit(raised > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) raised);
        }
        if (extendDays != null) {
            LocalDateTime base = sub.getCurrentPeriodEnd();
            LocalDateTime from = (base == null || base.isBefore(now)) ? now : base;
            sub.setCurrentPeriodEnd(from.plusDays(extendDays));
            if (sub.getCurrentPeriodStart() == null) {
                sub.setCurrentPeriodStart(now);
            }
            // Extension implies "this user should have access". A lapsed subscription is
            // brought back ONLY when it can genuinely run again: one that was cancelled or
            // completed at the gateway will never be renewed by a webhook, so flipping it
            // to ACTIVE produced a zombie that the daily sweep expired again a few days
            // later. Those get a clean, local-only grant instead.
            if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
                boolean deadAtGateway = !sub.isTrial()
                        && sub.getRazorpaySubscriptionId() != null
                        && !sub.getRazorpaySubscriptionId().isBlank()
                        && (sub.getStatus() == SubscriptionStatus.CANCELLED
                            || sub.getStatus() == SubscriptionStatus.COMPLETED);
                if (deadAtGateway) {
                    log.info("Subscription {} is cancelled/completed at Razorpay — issuing a fresh "
                            + "local grant instead of reviving it", sub.getId());
                    return adminGrantSubscription(adminUserId, targetUserId, sub.getPlan(), extendDays,
                            addAiGenerations != null ? sub.getAiGenerationsLimit() : null);
                }
                sub.setStatus(SubscriptionStatus.ACTIVE);
                sub.setCancelAtPeriodEnd(false);
            }
        }
        subscriptionRepository.save(sub);

        auditService.record(adminUserId, "ADMIN_SUBSCRIPTION_ADJUST", "SUBSCRIPTION", sub.getId(),
                "user=" + targetUserId
                        + (addAiGenerations != null ? " addAiGenerations=" + addAiGenerations : "")
                        + (extendDays != null ? " extendDays=" + extendDays : ""));
        log.info("Admin {} adjusted subscription {}: addAi={} extendDays={}",
                adminUserId, sub.getId(), addAiGenerations, extendDays);
        return SubscriptionResponse.from(sub);
    }

    /**
     * The subscription to SHOW the caller: the one actually entitling them, else their
     * most recent row so a lapsed account still gets a plan name and a renew prompt.
     *
     * Resolved through the same {@link #findEntitlingSubscription} gate every feature
     * enforces with. Matching on ACTIVE alone here (with "newest row of any status" as
     * the fallback) let the two disagree: a shop with a CANCELLED-but-still-paid-for plan
     * AND a newer abandoned checkout attempt was shown the attempt — "your subscription
     * has ended", "awaiting payment" — while the API happily served every feature.
     */
    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(String userId) {
        Subscription sub = findEntitlingSubscription(userId)
                .orElseGet(() -> subscriptionRepository
                        .findByUserIdOrderByCreatedAtDesc(userId)
                        .stream().findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("No subscription found")));
        return SubscriptionResponse.from(sub);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionResponse> getSubscriptionHistory(String userId) {
        return subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream().map(SubscriptionResponse::from).toList();
    }

    @Transactional
    public SubscriptionResponse cancelSubscription(String userId) {
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("No active subscription found"));

        // A free trial has no Razorpay subscription to cancel — calling the gateway with a
        // null id would blow up. Mark it not-to-renew and let it run out its window.
        //
        // It used to be flipped straight to EXPIRED, which destroyed the remaining trial
        // days on the spot: a retailer on day 2 of 14 who pressed "Cancel subscription" —
        // reading the paid-plan promise the same screen makes, "stays active till the end
        // of the period" — instantly lost the other 12 days with no undo. Nothing renews a
        // trial anyway, so simply not renewing it IS the cancellation.
        if (sub.isTrial() || sub.getRazorpaySubscriptionId() == null || sub.getRazorpaySubscriptionId().isBlank()) {
            sub.setCancelAtPeriodEnd(true);
            subscriptionRepository.save(sub);
            auditService.record(userId, "SUBSCRIPTION_CANCEL", "SUBSCRIPTION", sub.getId(), "trial=true");
            log.info("Trial set to not renew: user={} subId={} endsAt={}",
                    userId, sub.getId(), sub.getCurrentPeriodEnd());
            return SubscriptionResponse.from(sub);
        }

        try {
            JSONObject cancelRequest = new JSONObject();
            cancelRequest.put("cancel_at_cycle_end", 1);
            razorpayClient.subscriptions.cancel(sub.getRazorpaySubscriptionId(), cancelRequest);
            sub.setCancelAtPeriodEnd(true);
            subscriptionRepository.save(sub);
            auditService.record(userId, "SUBSCRIPTION_CANCEL", "SUBSCRIPTION", sub.getId(), "plan=" + sub.getPlan());
            log.info("Subscription cancel-at-period-end set: user={} subId={}", userId, sub.getId());
            billingEmailService.sendCancellationScheduled(sub);
        } catch (RazorpayException e) {
            log.error("Razorpay cancel failed: {}", e.getMessage());
            throw new IllegalStateException("Payment gateway error: " + e.getMessage());
        }

        return SubscriptionResponse.from(sub);
    }

    /**
     * End every live subscription of an account being deleted — at the gateway first, so
     * the card genuinely stops being charged, then locally.
     *
     * Account deletion used to scrub the row and stop there, which meant a "deleted"
     * customer kept getting billed by Razorpay every month with no way to reach the
     * cancel button. The gateway call is best-effort per subscription: a Razorpay outage
     * must not block someone from closing their account, and the local EXPIRED status is
     * what this app enforces regardless.
     */
    @Transactional
    public void endSubscriptionsForDeletedAccount(String userId) {
        List<Subscription> live = new java.util.ArrayList<>(
                subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE));
        live.addAll(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.HALTED));
        live.addAll(subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.CREATED));
        for (Subscription sub : live) {
            String rzpId = sub.getRazorpaySubscriptionId();
            if (!sub.isTrial() && rzpId != null && !rzpId.isBlank()) {
                try {
                    JSONObject cancelRequest = new JSONObject();
                    cancelRequest.put("cancel_at_cycle_end", 0);
                    razorpayClient.subscriptions.cancel(rzpId, cancelRequest);
                } catch (Exception e) {
                    // Best-effort on ANY failure: a gateway outage must not block someone
                    // from closing their account.
                    log.error("Razorpay cancel FAILED while deleting account {} (subscription {} may "
                            + "keep charging — settle manually): {}", userId, rzpId, e.getMessage());
                }
            }
            sub.setCancelAtPeriodEnd(true);
            // Closes the period as well as the status: a row left holding next month's
            // end date came back as an entitling CANCELLED one the moment Razorpay
            // echoed the cancellation back.
            expireLocally(sub);
            log.info("Subscription ended for deleted account: user={} subId={}", userId, sub.getId());
        }
        auditService.record(userId, "SUBSCRIPTIONS_ENDED_ON_DELETE", "USER", userId,
                "ended=" + live.size());
    }

    /**
     * Undo a scheduled cancellation while the plan is still running.
     *
     * Cancelling was a one-way door: it set {@code cancel_at_cycle_end} at the gateway
     * with no way back, AND the still-ACTIVE row then blocked re-subscribing to the same
     * plan ("You're already on the X plan") and blocked every downgrade — so a misclick
     * left the retailer stuck until the period ran out. Resuming clears the flag on both
     * sides by re-creating the gateway subscription's renewal intent.
     */
    @Transactional
    public SubscriptionResponse resumeSubscription(String userId) {
        Subscription sub = findEntitlingSubscription(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No subscription to resume"));
        if (!sub.isCancelAtPeriodEnd() && sub.getStatus() == SubscriptionStatus.ACTIVE) {
            return SubscriptionResponse.from(sub); // nothing scheduled — already running
        }
        if (sub.getCurrentPeriodEnd() != null && sub.getCurrentPeriodEnd().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException(
                    "This plan's period has already ended — subscribe again to start a new one.");
        }

        String rzpId = sub.getRazorpaySubscriptionId();
        if (!sub.isTrial() && rzpId != null && !rzpId.isBlank()) {
            // Razorpay has no "un-cancel": a subscription cancelled at cycle end keeps
            // running to that date and then stops. Resuming therefore means the customer
            // must start a fresh gateway subscription for the NEXT cycle. We clear the
            // local flag so their access and the UI agree, and tell the caller plainly.
            throw new IllegalStateException(
                    "Your plan is set to end on "
                    + (sub.getCurrentPeriodEnd() != null
                        ? sub.getCurrentPeriodEnd().toLocalDate().toString() : "its period end")
                    + " and stays fully active until then. Razorpay can't un-cancel a plan, so "
                    + "to keep going, subscribe again — the new plan starts when this one ends, "
                    + "with no double billing.");
        }

        sub.setCancelAtPeriodEnd(false);
        subscriptionRepository.save(sub);
        auditService.record(userId, "SUBSCRIPTION_RESUME", "SUBSCRIPTION", sub.getId(),
                "plan=" + sub.getPlan() + " trial=" + sub.isTrial());
        log.info("Subscription resumed: user={} subId={}", userId, sub.getId());
        return SubscriptionResponse.from(sub);
    }

    /**
     * Read-only image-quota gate: throws if {@code userId} has no active subscription or
     * has spent their effective image allowance (monthly limit + purchased pay-per-image
     * credits), but does NOT increment. Used as a cheap fail-fast pre-flight before the
     * actual charge lands once the AI work succeeds (see {@link #incrementAiUsage}) —
     * e.g. guest wall-detection billed to the shop, so a failed run never costs a credit.
     */
    @Transactional(readOnly = true)
    public void assertAiQuotaAvailable(String userId) {
        assertAiQuotaAvailable(userId, false);
    }

    /**
     * @param holdsReservation when true the caller already owns a HELD image credit for
     *        this run (an access-code project the shop paid for at code-generation time),
     *        so only the "is there a billable subscription at all" half of the gate
     *        applies — the allowance check would otherwise reject a run that has in fact
     *        already been paid for.
     */
    @Transactional(readOnly = true)
    public void assertAiQuotaAvailable(String userId, boolean holdsReservation) {
        Subscription sub = requireBillableSubscription(userId);
        if (holdsReservation) {
            return;
        }
        if (sub.getAiGenerationsUsed() + sub.getReservedImages() >= effectiveImageAllowance(sub)) {
            throw new com.gridstore.huevista.common.exception.ImageLimitReachedException(
                    "Monthly image limit reached (" + sub.getAiGenerationsLimit() + "). "
                    + "Spend " + pointsPriceImage + " points on an extra image, "
                    + "upgrade your plan, or wait for the next billing cycle.");
        }
    }

    /**
     * Spend one image credit HELD by an access code. Moves the hold into usage on the
     * subscription; returns false when the shop had no hold left (the caller then falls
     * back to a normal charge).
     *
     * Unlike the sibling charge methods this JOINS the caller's transaction rather than
     * committing independently: it is paired with a matching decrement on the access code
     * itself, and the two counters must move together or drift apart. The async billing
     * path has no ambient transaction, so it still commits on its own there.
     */
    @Transactional
    public boolean consumeReservedImage(String userId) {
        return findEntitlingSubscription(userId)
                .map(sub -> subscriptionRepository.consumeReservedImage(sub.getId()) == 1)
                .orElse(false);
    }

    /**
     * Hand {@code count} held image credits back to a shop — a code was revoked, or
     * expired without anyone redeeming it. Floored at zero.
     *
     * Joins the caller's transaction so the refund is atomic with the revoke that caused
     * it: returning the credits in a separate transaction meant a revoke that later rolled
     * back still gave the quota away.
     */
    @Transactional
    public void releaseReservedImages(String userId, int count) {
        if (count <= 0) return;
        findEntitlingSubscription(userId)
                .ifPresent(sub -> subscriptionRepository.releaseReservedImages(sub.getId(), count));
    }

    /**
     * Read-only auto-mask gate for the AI wall-detection step (the optional mask created
     * automatically AFTER the compulsory photo clean-up). Throws 402-tagged
     * {@code AUTO_MASK_UNAVAILABLE} when the plan has no auto-masking at all (Starter is
     * manual-only) or the monthly auto-mask allowance is spent — manual masking stays
     * available either way, so the caller should steer the user there or to an upgrade.
     */
    @Transactional(readOnly = true)
    public void assertAutoMaskQuotaAvailable(String userId) {
        Subscription sub = requireBillableSubscription(userId);
        long allowance = (long) sub.getAutoMasksLimit() + sub.getPurchasedAutoMaskCredits();
        if (allowance <= 0) {
            throw new com.gridstore.huevista.common.exception.AutoMaskUnavailableException(
                    "AI wall detection isn't included in the " + sub.getPlan().getDisplayName()
                    + " plan — mark walls yourself with click-to-segment (free, unlimited), "
                    + "or upgrade to a plan with AI auto-masking.");
        }
        if (sub.getAutoMasksUsed() >= allowance) {
            throw new com.gridstore.huevista.common.exception.AutoMaskUnavailableException(
                    "Monthly AI wall-detection limit reached (" + sub.getAutoMasksLimit() + "). "
                    + "Spend " + pointsPriceAutoMask + " points on one more, mark "
                    + "walls yourself with click-to-segment (free, unlimited), upgrade your plan, "
                    + "or wait for the next billing cycle.");
        }
    }

    /**
     * Charges one AI auto-mask run to {@code userId} once wall detection has actually
     * succeeded. Best-effort like {@link #incrementAiUsage} — a missing subscription is a
     * no-op and the increment is not limit-gated because the run already happened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementAutoMaskUsage(String userId) {
        findEntitlingSubscription(userId)
                .ifPresent(sub -> subscriptionRepository.incrementAutoMaskUsage(sub.getId()));
    }

    /**
     * Credit one pay-per-image overage purchase (verified Rs. 50 + GST payment) to the
     * user's ACTIVE subscription. Throws when there is no active subscription — the
     * payment flow requires one up-front, so this only trips on a race with expiry.
     */
    @Transactional
    public SubscriptionResponse creditPurchasedImage(String userId) {
        Subscription sub = findEntitlingSubscription(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active subscription to credit — contact support with your payment id."));
        subscriptionRepository.addPurchasedImageCredits(sub.getId(), 1);
        Subscription fresh = subscriptionRepository.findById(sub.getId()).orElse(sub);
        log.info("Image overage credit added: user={} subId={} credits={}",
                userId, fresh.getId(), fresh.getPurchasedImageCredits());
        return SubscriptionResponse.from(fresh);
    }

    /**
     * Credit one pay-per-use AI auto-mask purchase (verified wallet debit of
     * Rs. 25 + GST) to the user's ACTIVE subscription.
     */
    @Transactional
    public SubscriptionResponse creditPurchasedAutoMask(String userId) {
        Subscription sub = findEntitlingSubscription(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active subscription to credit — contact support with your payment id."));
        subscriptionRepository.addPurchasedAutoMaskCredits(sub.getId(), 1);
        Subscription fresh = subscriptionRepository.findById(sub.getId()).orElse(sub);
        log.info("Auto-mask overage credit added: user={} subId={} credits={}",
                userId, fresh.getId(), fresh.getPurchasedAutoMaskCredits());
        return SubscriptionResponse.from(fresh);
    }

    /**
     * The subscription that currently entitles {@code userId} — ACTIVE, or a CANCELLED
     * plan still inside the period it was paid for. Every quota gate and every charge
     * routes through this so "cancelled, active till period end" means the same thing in
     * the UI and in the enforcement path.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Subscription> findEntitlingSubscription(String userId) {
        return subscriptionRepository.findEntitling(
                        userId, SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLED,
                        LocalDateTime.now())
                .stream().findFirst();
    }

    private Subscription requireBillableSubscription(String userId) {
        return findEntitlingSubscription(userId)
                .orElseThrow(() -> new QuotaExceededException(
                        "No active subscription. Subscribe to use AI features."));
    }

    /** Monthly image limit + purchased overage credits, clamped against int overflow. */
    private static int effectiveImageAllowance(Subscription sub) {
        long allowance = (long) sub.getAiGenerationsLimit() + sub.getPurchasedImageCredits();
        return allowance > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) allowance;
    }

    /**
     * Atomically reserve one AI generation up-front and limit-gated. The check and the
     * increment are a single conditional UPDATE, so two concurrent requests can never both
     * consume the last remaining credit (the old read-then-write let a user with 1 credit
     * left fire N parallel requests and get N generations). Throws when there is no active
     * subscription or the monthly limit is already reached. Pair with {@link #refundAiUsage}
     * so a run that later fails returns its credit and stays free.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveAiUsage(String userId) {
        Subscription sub = requireBillableSubscription(userId);
        int reserved = subscriptionRepository.incrementAiUsageIfWithinLimit(sub.getId());
        if (reserved == 0) {
            throw new com.gridstore.huevista.common.exception.ImageLimitReachedException(
                    "Monthly image limit reached (" + sub.getAiGenerationsLimit() + "). " +
                    "Buy an extra image, upgrade your plan, or wait for the next billing cycle.");
        }
    }

    /**
     * Return a previously reserved credit when the AI work failed, so a failed run stays
     * free. Best-effort and floored at zero. Commits independently of the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refundAiUsage(String userId) {
        findEntitlingSubscription(userId)
                .ifPresent(sub -> subscriptionRepository.decrementAiUsage(sub.getId()));
    }

    /**
     * Charges one AI generation to {@code userId} once the work has actually succeeded.
     * Best-effort: a missing subscription is a no-op (we never charge an account that can't
     * be billed), and the increment is not limit-gated because the generation already
     * happened. The increment is a single atomic UPDATE — no read-modify-write — so
     * concurrent charges can't lose each other. Commits independently of the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementAiUsage(String userId) {
        findEntitlingSubscription(userId)
                .ifPresent(sub -> subscriptionRepository.incrementAiUsage(sub.getId()));
    }

    /**
     * Activated by Razorpay webhook: subscription.activated
     */
    @Transactional
    public void activateSubscription(String razorpaySubscriptionId, long chargeAt, long currentEnd) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            // Same one-way door the verify path enforces: an ended subscription is never
            // brought back in place, whichever side the event arrives from.
            if (TERMINAL_STATUSES.contains(sub.getStatus())) {
                log.info("Ignoring activation webhook for a {} subscription: {}",
                        sub.getStatus(), razorpaySubscriptionId);
                return;
            }
            // The checkout-verify fast path usually activates first; only the genuine
            // transition sends the confirmation email (no duplicate on the webhook echo).
            boolean wasActive = sub.getStatus() == SubscriptionStatus.ACTIVE;
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setTrial(false);
            // Only the transition INTO active sets the period. A late echo of an
            // activation the verify path already handled used to push currentPeriodStart
            // forward to "now", which moved the renewal date and — before the charge
            // counter — made the next real renewal read as a first charge.
            if (!wasActive) {
                LocalDateTime now = LocalDateTime.now();
                boolean scheduled = sub.getCurrentPeriodStart() != null
                        && sub.getCurrentPeriodStart().isAfter(now);
                if (!scheduled) {
                    sub.setCurrentPeriodStart(now);
                    sub.setCurrentPeriodEnd(resolvePeriodEnd(currentEnd));
                }
            }
            subscriptionRepository.save(sub);
            // End any other still-active subscription (free trial, or the plan this
            // one upgrades from) now that the new paid plan is live. A plan scheduled to
            // start later supersedes nothing yet — the one it replaces is still running
            // out the period it was paid for.
            boolean inForce = sub.getCurrentPeriodStart() == null
                    || !sub.getCurrentPeriodStart().isAfter(LocalDateTime.now());
            if (inForce) {
                supersedeActiveSubscriptions(sub.getUser().getId(), sub.getId());
            }
            log.info("Subscription activated: {}", razorpaySubscriptionId);
            if (!wasActive) {
                billingEmailService.sendSubscriptionActivated(sub);
            }
        });
    }

    /**
     * Activated by Razorpay webhook: subscription.cancelled
     */
    @Transactional
    public void markCancelled(String razorpaySubscriptionId) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            // An upgrade (and account deletion) cancels the old plan at the gateway, so
            // this webhook echoes back for a row we already retired. Writing CANCELLED
            // over EXPIRED un-retired it: findEntitling counts a CANCELLED row as still
            // entitling while its period runs, so the superseded plan came back as a free
            // second entitlement. It is already ended — leave it alone. (The email is
            // suppressed for the same reason: nobody who just upgraded wants to be told
            // their subscription has ended.)
            if (sub.getStatus() == SubscriptionStatus.EXPIRED) {
                log.info("Ignoring cancellation webhook for an already-retired subscription: {}",
                        razorpaySubscriptionId);
                return;
            }
            boolean wasCancelled = sub.getStatus() == SubscriptionStatus.CANCELLED;
            sub.setStatus(SubscriptionStatus.CANCELLED);
            subscriptionRepository.save(sub);
            log.info("Subscription cancelled: {}", razorpaySubscriptionId);
            if (!wasCancelled) {
                billingEmailService.sendSubscriptionEnded(sub);
            }
        });
    }

    /**
     * Activated by Razorpay webhook: subscription.completed
     */
    @Transactional
    public void markCompleted(String razorpaySubscriptionId) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            sub.setStatus(SubscriptionStatus.COMPLETED);
            subscriptionRepository.save(sub);
            log.info("Subscription completed: {}", razorpaySubscriptionId);
        });
    }

    /**
     * Activated by Razorpay webhook: subscription.halted (payment failure)
     */
    @Transactional
    public void markHalted(String razorpaySubscriptionId) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            boolean wasHalted = sub.getStatus() == SubscriptionStatus.HALTED;
            sub.setStatus(SubscriptionStatus.HALTED);
            subscriptionRepository.save(sub);
            log.warn("Subscription halted due to payment failure: {}", razorpaySubscriptionId);
            if (!wasHalted) {
                billingEmailService.sendPaymentFailed(sub);
            }
        });
    }

    /**
     * On successful renewal (payment.captured), reset monthly AI usage and update period dates.
     */
    @Transactional
    public void handlePaymentCaptured(String razorpaySubscriptionId, long currentEnd) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            // Classify BEFORE mutating, by counting charges rather than guessing from
            // dates. The old test ("did this period start more than 7 days ago?") had to
            // guess because nothing recorded the charges; it misread a late activation
            // echo, and a plan scheduled to begin next month — whose period start is in
            // the FUTURE on its first charge — would have defeated it entirely.
            //
            // Cycle 0 is the charge that starts the plan; the buyer was already emailed
            // when it activated, so only an activation this webhook itself performs sends
            // that mail. Every later cycle is a renewal, including a HALTED recovery.
            SubscriptionStatus previousStatus = sub.getStatus();
            boolean firstCharge = sub.getBillingCyclesCharged() == 0;
            boolean alreadyAnnounced = previousStatus == SubscriptionStatus.ACTIVE;
            sub.setBillingCyclesCharged(sub.getBillingCyclesCharged() + 1);

            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAiGenerationsUsed(0);
            sub.setAutoMasksUsed(0);
            sub.setPdfDownloadsUsed(0);
            // Purchased pay-per-image credits deliberately survive renewal — a paid
            // credit never evaporates. reservedImages is likewise NOT reset: a code
            // issued last cycle is still redeemable this one, so the hold behind it must
            // outlive the counter reset (zeroing it silently gave those projects away).
            // Older rows (pre-quota-split) were backfilled with base allowances; refreshing
            // from the plan on every renewal also picks up any plan-limit changes.
            sub.setPdfImageLimit(sub.getPlan().getPdfImageLimit());
            if (sub.getPdfDownloadsLimit() < sub.getPlan().getMonthlyPdfLimit()) {
                sub.setPdfDownloadsLimit(sub.getPlan().getMonthlyPdfLimit());
            }
            if (sub.getAutoMasksLimit() < sub.getPlan().getMonthlyAutoMaskLimit()) {
                sub.setAutoMasksLimit(sub.getPlan().getMonthlyAutoMaskLimit());
            }
            sub.setCurrentPeriodStart(LocalDateTime.now());
            LocalDateTime newEnd = resolvePeriodEnd(currentEnd);
            // Never shrink an already-known period end. subscription.charged carries the real
            // current_end while a bare payment.captured only approximates +30 days, and the
            // two events can arrive in either order for the same renewal.
            if (sub.getCurrentPeriodEnd() == null || newEnd.isAfter(sub.getCurrentPeriodEnd())) {
                sub.setCurrentPeriodEnd(newEnd);
            }
            subscriptionRepository.save(sub);
            if (firstCharge) {
                // A plan bought while another was winding down starts here, a month after
                // it was authorized: take over from whatever the shop was still on. For an
                // immediate purchase the verify path already did this and there is nothing
                // left to supersede.
                supersedeActiveSubscriptions(sub.getUser().getId(), sub.getId());
            }
            log.info("Subscription renewed, AI usage reset: {}", razorpaySubscriptionId);
            if (firstCharge && !alreadyAnnounced) {
                billingEmailService.sendSubscriptionActivated(sub);
            } else if (!firstCharge) {
                billingEmailService.sendSubscriptionRenewed(sub);
            }
            // else: the charge webhook echoing a just-verified activation — already emailed.
        });
    }

    /**
     * Safety net: expire subscriptions whose period has ended and weren't renewed.
     * Runs daily at 01:00. Queries only the already-lapsed rows instead of scanning the
     * whole table. A free trial has no renewal so it expires the moment its window passes;
     * a PAID plan is renewed by webhook, so it is only hard-expired after a grace period —
     * otherwise a delayed renewal webhook would cut off a customer who has actually paid.
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void expireStaleSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime paidCutoff = now.minusDays(RENEWAL_GRACE_DAYS);
        subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(SubscriptionStatus.ACTIVE, now)
                .forEach(s -> {
                    boolean expire = s.isTrial() || s.getCurrentPeriodEnd().isBefore(paidCutoff);
                    if (expire) {
                        s.setStatus(SubscriptionStatus.EXPIRED);
                        subscriptionRepository.save(s);
                        log.info("Subscription expired: id={} trial={}", s.getId(), s.isTrial());
                    }
                });
    }

    /**
     * End every other ACTIVE subscription of {@code userId} except {@code keepSubId},
     * called when a paid plan goes live so a retailer never holds two active plans.
     * A superseded free trial simply expires locally; a superseded PAID plan (the
     * upgrade case) is also cancelled at Razorpay immediately so its next renewal
     * never charges the card again. The gateway cancel is best-effort: a Razorpay
     * hiccup must not fail the activation of the plan the user just paid for — the
     * local EXPIRED status is what the app enforces either way.
     */
    private void supersedeActiveSubscriptions(String userId, String keepSubId) {
        subscriptionRepository.findByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)
                .forEach(existing -> {
                    if (existing.getId().equals(keepSubId)) {
                        return;
                    }
                    boolean paidAtGateway = !existing.isTrial()
                            && existing.getRazorpaySubscriptionId() != null
                            && !existing.getRazorpaySubscriptionId().isBlank();
                    if (paidAtGateway) {
                        try {
                            JSONObject cancelRequest = new JSONObject();
                            cancelRequest.put("cancel_at_cycle_end", 0);
                            razorpayClient.subscriptions.cancel(
                                    existing.getRazorpaySubscriptionId(), cancelRequest);
                        } catch (Exception e) {
                            // Any gateway trouble, not just a RazorpayException: this runs
                            // inside the activation of a plan the shop has ALREADY paid
                            // for, and nothing that happens to the old subscription is
                            // worth failing that. Local expiry is what we enforce anyway.
                            log.warn("Razorpay cancel of superseded subscription {} failed "
                                    + "(local expiry still applied): {}",
                                    existing.getRazorpaySubscriptionId(), e.getMessage());
                        }
                    }
                    carryOverCredits(existing, keepSubId);
                    expireLocally(existing);
                    log.info("Subscription superseded by new activation: user={} oldSubId={} trial={}",
                            userId, existing.getId(), existing.isTrial());
                });
    }

    /**
     * Move everything the shop has already paid for off a subscription being retired and
     * onto the one replacing it: purchased image and auto-mask credits, and the image
     * holds standing behind access codes that customers have not redeemed yet.
     *
     * Without this an upgrade quietly destroyed all three. Purchased credits are real
     * money and the entity says outright that one "never evaporates" — it survived
     * renewal but not an upgrade. The holds were worse than lost: the CODES kept their
     * side of the reservation, so when the customer finally redeemed one,
     * SegmentationService found no hold left on the subscription and fell through to a
     * normal charge, billing the shop a second time for a project it had already paid
     * for when it issued the code.
     */
    private void carryOverCredits(Subscription from, String toSubId) {
        int images = from.getPurchasedImageCredits();
        int masks = from.getPurchasedAutoMaskCredits();
        int held = from.getReservedImages();
        if (images <= 0 && masks <= 0 && held <= 0) {
            return;
        }
        if (images > 0) {
            subscriptionRepository.addPurchasedImageCredits(toSubId, images);
            from.setPurchasedImageCredits(0);
        }
        if (masks > 0) {
            subscriptionRepository.addPurchasedAutoMaskCredits(toSubId, masks);
            from.setPurchasedAutoMaskCredits(0);
        }
        if (held > 0) {
            subscriptionRepository.addReservedImages(toSubId, held);
            from.setReservedImages(0);
        }
        log.info("Carried credits onto subscription {}: images={} autoMasks={} held={}",
                toSubId, images, masks, held);
    }

    /**
     * Retire a subscription on our side: EXPIRED, and its paid period closed off at now.
     *
     * Truncating the period is what makes EXPIRED stick. {@code findEntitling} treats a
     * CANCELLED row with a future period end as still entitling, so a superseded (or
     * deleted-account) row left holding next month's end date came back to life the
     * moment Razorpay echoed {@code subscription.cancelled} — handing back the plan the
     * shop had just upgraded away from, for free.
     */
    private void expireLocally(Subscription sub) {
        sub.setStatus(SubscriptionStatus.EXPIRED);
        LocalDateTime now = LocalDateTime.now();
        if (sub.getCurrentPeriodEnd() == null || sub.getCurrentPeriodEnd().isAfter(now)) {
            sub.setCurrentPeriodEnd(now);
        }
        subscriptionRepository.save(sub);
    }

    /** Scale a plan's monthly quota by the billed quantity, clamped to avoid int overflow. */
    private int scaledLimit(int baseLimit, int quantity) {
        long scaled = (long) baseLimit * Math.max(1, quantity);
        return scaled > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) scaled;
    }

    private String resolveRazorpayPlanId(Plan plan) {
        return switch (plan) {
            case STARTER -> planIdStarter;
            case PROFESSIONAL -> planIdProfessional;
            case BUSINESS -> planIdBusiness;
            // Neither is sold through Checkout: FREE is granted, ENTERPRISE is arranged.
            case FREE, ENTERPRISE -> "";
        };
    }

    /** Convert Razorpay's epoch-seconds period end to local time, falling back to +30 days
     *  when the payload didn't carry a usable value (e.g. a bare payment.captured). */
    private LocalDateTime resolvePeriodEnd(long epochSeconds) {
        if (epochSeconds <= 0) {
            return LocalDateTime.now().plusDays(30);
        }
        return java.time.Instant.ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime();
    }
}
