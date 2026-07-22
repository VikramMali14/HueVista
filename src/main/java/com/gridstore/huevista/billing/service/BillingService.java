package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.CreateSubscriptionRequest;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifySubscriptionRequest;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
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
    private final UserRepository userRepository;
    private final RazorpayClient razorpayClient;
    private final com.gridstore.huevista.common.audit.AuditService auditService;
    private final BillingEmailService billingEmailService;

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

    @Transactional
    public SubscriptionResponse createSubscription(String userId, CreateSubscriptionRequest request) {
        if (request.getPlan() == Plan.ENTERPRISE) {
            throw new IllegalArgumentException("Enterprise plans require manual setup. Please contact sales.");
        }

        // An active free trial never blocks buying a plan (the trial is superseded once
        // the plan activates). An active PAID subscription allows exactly one in-place
        // change: an UPGRADE to a higher tier — the old plan is cancelled and replaced
        // the moment the new one activates (see verifyAndActivateSubscription /
        // activateSubscription). Same tier or a downgrade still requires cancelling
        // first, so nobody accidentally double-pays for a sideways move.
        Subscription activePaid = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .filter(s -> !s.isTrial())
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

        try {
            JSONObject subRequest = new JSONObject();
            subRequest.put("plan_id", razorpayPlanId);
            subRequest.put("total_count", subscriptionTotalCount);
            subRequest.put("quantity", request.getQuantity());
            subRequest.put("customer_notify", 1);

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
            log.info("Subscription created: user={} plan={} rzpId={}", userId, request.getPlan(), rzpSubId);
            // razorpayKeyId lets the browser open the in-app Checkout for this subscription;
            // paymentUrl (hosted short_url) is kept as a fallback for clients that can't.
            return SubscriptionResponse.from(sub, paymentUrl, keyId);

        } catch (RazorpayException e) {
            log.error("Razorpay subscription creation failed: {}", e.getMessage());
            throw new IllegalStateException("Payment gateway error: " + e.getMessage());
        }
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

        if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
            LocalDateTime now = LocalDateTime.now();
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setTrial(false);
            sub.setCurrentPeriodStart(now);
            // Approximate the first monthly period; the renewal webhook corrects it later.
            sub.setCurrentPeriodEnd(now.plusMonths(1));
            subscriptionRepository.save(sub);
            // The retailer just paid — end any other still-active subscription (a free
            // trial, or the smaller plan this purchase upgrades from) so they never hold
            // two active plans or get double-billed.
            supersedeActiveSubscriptions(sub.getUser().getId(), sub.getId());
            auditService.record(userId, "SUBSCRIPTION_ACTIVATE", "SUBSCRIPTION", sub.getId(),
                    "plan=" + sub.getPlan() + " rzpId=" + request.getSubscriptionId());
            log.info("Subscription activated via checkout verify: user={} subId={}", userId, sub.getId());
            billingEmailService.sendSubscriptionActivated(sub);
        }
        return SubscriptionResponse.from(sub);
    }

    /**
     * Grant a free trial subscription (no Razorpay) so a newly-signed-up retailer can
     * use AI features immediately. Status is ACTIVE with a {@code trialDays} window;
     * the daily {@link #expireStaleSubscriptions()} job flips it to EXPIRED at the end.
     * Idempotent — no-op (returns the existing one) if the user already has an active sub.
     */
    @Transactional
    public SubscriptionResponse grantTrial(String userId, Plan plan, int trialDays) {
        if (subscriptionRepository.existsByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)) {
            return getCurrentSubscription(userId);
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Plan p = (plan == null || plan == Plan.ENTERPRISE) ? Plan.PROFESSIONAL : plan;
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

        subscriptionRepository.findByUserIdAndStatus(targetUserId, SubscriptionStatus.ACTIVE)
                .forEach(existing -> {
                    existing.setStatus(SubscriptionStatus.EXPIRED);
                    subscriptionRepository.save(existing);
                    log.info("Subscription superseded by admin grant: user={} subId={}",
                            targetUserId, existing.getId());
                });

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
            // Extension implies "this user should have access" — bring a lapsed
            // subscription back to life. A still-ACTIVE one is left as-is.
            if (sub.getStatus() != SubscriptionStatus.ACTIVE) {
                sub.setStatus(SubscriptionStatus.ACTIVE);
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

    @Transactional(readOnly = true)
    public SubscriptionResponse getCurrentSubscription(String userId) {
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
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
        // null id would blow up. Just end the trial locally.
        if (sub.isTrial() || sub.getRazorpaySubscriptionId() == null || sub.getRazorpaySubscriptionId().isBlank()) {
            sub.setStatus(SubscriptionStatus.EXPIRED);
            subscriptionRepository.save(sub);
            auditService.record(userId, "SUBSCRIPTION_CANCEL", "SUBSCRIPTION", sub.getId(), "trial=true");
            log.info("Trial ended by cancel: user={} subId={}", userId, sub.getId());
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
     * Read-only image-quota gate: throws if {@code userId} has no active subscription or
     * has spent their effective image allowance (monthly limit + purchased pay-per-image
     * credits), but does NOT increment. Used as a cheap fail-fast pre-flight before the
     * actual charge lands once the AI work succeeds (see {@link #incrementAiUsage}) —
     * e.g. guest wall-detection billed to the shop, so a failed run never costs a credit.
     */
    @Transactional(readOnly = true)
    public void assertAiQuotaAvailable(String userId) {
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new QuotaExceededException(
                        "No active subscription. Subscribe to use AI features."));
        if (sub.getAiGenerationsUsed() >= effectiveImageAllowance(sub)) {
            throw new com.gridstore.huevista.common.exception.ImageLimitReachedException(
                    "Monthly image limit reached (" + sub.getAiGenerationsLimit() + "). " +
                    "Buy an extra image for Rs. " + (Plan.imageOveragePriceWithTaxInPaise() / 100)
                    + ", upgrade your plan, or wait for the next billing cycle.");
        }
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
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new QuotaExceededException(
                        "No active subscription. Subscribe to use AI features."));
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
                    + "Pay Rs. " + (Plan.autoMaskOveragePriceWithTaxInPaise() / 100.0)
                    + " from your wallet for one more, mark walls yourself with "
                    + "click-to-segment (free, unlimited), upgrade your plan, or wait for "
                    + "the next billing cycle.");
        }
    }

    /**
     * Charges one AI auto-mask run to {@code userId} once wall detection has actually
     * succeeded. Best-effort like {@link #incrementAiUsage} — a missing subscription is a
     * no-op and the increment is not limit-gated because the run already happened.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementAutoMaskUsage(String userId) {
        subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .ifPresent(sub -> subscriptionRepository.incrementAutoMaskUsage(sub.getId()));
    }

    /**
     * Credit one pay-per-image overage purchase (verified Rs. 50 + GST payment) to the
     * user's ACTIVE subscription. Throws when there is no active subscription — the
     * payment flow requires one up-front, so this only trips on a race with expiry.
     */
    @Transactional
    public SubscriptionResponse creditPurchasedImage(String userId) {
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
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
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active subscription to credit — contact support with your payment id."));
        subscriptionRepository.addPurchasedAutoMaskCredits(sub.getId(), 1);
        Subscription fresh = subscriptionRepository.findById(sub.getId()).orElse(sub);
        log.info("Auto-mask overage credit added: user={} subId={} credits={}",
                userId, fresh.getId(), fresh.getPurchasedAutoMaskCredits());
        return SubscriptionResponse.from(fresh);
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
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new QuotaExceededException(
                        "No active subscription. Subscribe to use AI features."));
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
        subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
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
        subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .ifPresent(sub -> subscriptionRepository.incrementAiUsage(sub.getId()));
    }

    /**
     * Activated by Razorpay webhook: subscription.activated
     */
    @Transactional
    public void activateSubscription(String razorpaySubscriptionId, long chargeAt, long currentEnd) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            // The checkout-verify fast path usually activates first; only the genuine
            // transition sends the confirmation email (no duplicate on the webhook echo).
            boolean wasActive = sub.getStatus() == SubscriptionStatus.ACTIVE;
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setTrial(false);
            sub.setCurrentPeriodStart(LocalDateTime.now());
            sub.setCurrentPeriodEnd(resolvePeriodEnd(currentEnd));
            subscriptionRepository.save(sub);
            // End any other still-active subscription (free trial, or the plan this
            // one upgrades from) now that the new paid plan is live.
            supersedeActiveSubscriptions(sub.getUser().getId(), sub.getId());
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
            boolean wasCancelled = sub.getStatus() == SubscriptionStatus.CANCELLED;
            // An upgrade cancels the old plan at the gateway; when that webhook echoes
            // back the user already holds a new ACTIVE plan — a "your subscription has
            // ended" email would only alarm someone who just paid for a bigger one.
            boolean superseded = sub.getStatus() == SubscriptionStatus.EXPIRED
                    && subscriptionRepository.existsByUserIdAndStatus(
                            sub.getUser().getId(), SubscriptionStatus.ACTIVE);
            sub.setStatus(SubscriptionStatus.CANCELLED);
            subscriptionRepository.save(sub);
            log.info("Subscription cancelled: {}", razorpaySubscriptionId);
            if (!wasCancelled && !superseded) {
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
            // Classify BEFORE mutating: the very first charge arrives right after checkout
            // (activation already emailed a receipt), while a real renewal lands roughly a
            // month into an ACTIVE period. HALTED→captured is a recovery — quota refreshed,
            // so it reads as a renewal too.
            SubscriptionStatus previousStatus = sub.getStatus();
            LocalDateTime previousStart = sub.getCurrentPeriodStart();
            boolean firstCharge = previousStatus == SubscriptionStatus.CREATED || previousStart == null;
            boolean renewal = !firstCharge
                    && (previousStatus == SubscriptionStatus.HALTED
                        || previousStart.isBefore(LocalDateTime.now().minusDays(7)));

            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAiGenerationsUsed(0);
            sub.setAutoMasksUsed(0);
            sub.setPdfDownloadsUsed(0);
            // Purchased pay-per-image credits deliberately survive renewal — a paid
            // credit never evaporates.
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
            log.info("Subscription renewed, AI usage reset: {}", razorpaySubscriptionId);
            if (firstCharge) {
                billingEmailService.sendSubscriptionActivated(sub);
            } else if (renewal) {
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
                        } catch (RazorpayException e) {
                            log.warn("Razorpay cancel of superseded subscription {} failed "
                                    + "(local expiry still applied): {}",
                                    existing.getRazorpaySubscriptionId(), e.getMessage());
                        }
                    }
                    existing.setStatus(SubscriptionStatus.EXPIRED);
                    subscriptionRepository.save(existing);
                    log.info("Subscription superseded by new activation: user={} oldSubId={} trial={}",
                            userId, existing.getId(), existing.isTrial());
                });
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
            case ENTERPRISE -> "";
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
