package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.dto.CreateSubscriptionRequest;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
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
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BillingService {

    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final RazorpayClient razorpayClient;
    private final com.gridstore.huevista.common.audit.AuditService auditService;

    @Value("${razorpay.plan.starter:}")
    private String planIdStarter;

    @Value("${razorpay.plan.professional:}")
    private String planIdProfessional;

    @Value("${razorpay.plan.business:}")
    private String planIdBusiness;

    private static final Map<Plan, String> RAZORPAY_PLAN_IDS_FIELD = Map.of();

    @Transactional
    public SubscriptionResponse createSubscription(String userId, CreateSubscriptionRequest request) {
        if (request.getPlan() == Plan.ENTERPRISE) {
            throw new IllegalArgumentException("Enterprise plans require manual setup. Please contact sales.");
        }

        if (subscriptionRepository.existsByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)) {
            throw new IllegalStateException("You already have an active subscription. Cancel it first.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String razorpayPlanId = resolveRazorpayPlanId(request.getPlan());
        if (razorpayPlanId.isBlank()) {
            throw new IllegalStateException("Razorpay plan ID not configured for: " + request.getPlan());
        }

        try {
            JSONObject subRequest = new JSONObject();
            subRequest.put("plan_id", razorpayPlanId);
            subRequest.put("total_count", 12); // 12 billing cycles (1 year)
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
                    .aiGenerationsLimit(request.getPlan().getMonthlyAiLimit())
                    .build();

            subscriptionRepository.save(sub);
            log.info("Subscription created: user={} plan={} rzpId={}", userId, request.getPlan(), rzpSubId);
            return SubscriptionResponse.from(sub, paymentUrl);

        } catch (RazorpayException e) {
            log.error("Razorpay subscription creation failed: {}", e.getMessage());
            throw new IllegalStateException("Payment gateway error: " + e.getMessage());
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
                .aiGenerationsLimit(p.getMonthlyAiLimit())
                .build();
        subscriptionRepository.save(sub);
        log.info("Trial granted: user={} plan={} days={}", userId, p, trialDays);
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

        try {
            JSONObject cancelRequest = new JSONObject();
            cancelRequest.put("cancel_at_cycle_end", 1);
            razorpayClient.subscriptions.cancel(sub.getRazorpaySubscriptionId(), cancelRequest);
            sub.setCancelAtPeriodEnd(true);
            subscriptionRepository.save(sub);
            auditService.record(userId, "SUBSCRIPTION_CANCEL", "SUBSCRIPTION", sub.getId(), "plan=" + sub.getPlan());
            log.info("Subscription cancel-at-period-end set: user={} subId={}", userId, sub.getId());
        } catch (RazorpayException e) {
            log.error("Razorpay cancel failed: {}", e.getMessage());
            throw new IllegalStateException("Payment gateway error: " + e.getMessage());
        }

        return SubscriptionResponse.from(sub);
    }

    /**
     * Called before any AI generation. Throws if the user has no active subscription or has hit their limit.
     * Increments the usage counter atomically within the same transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void checkAndIncrementAiUsage(String userId) {
        // REQUIRES_NEW so the usage increment commits independently of the caller's transaction.
        // The AI recommendation flow runs in a read-only transaction; with the old default the
        // increment joined that read-only tx and was never flushed — the quota never decremented
        // and was effectively unlimited.
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new QuotaExceededException(
                        "No active subscription. Subscribe to use AI features."));

        if (sub.getAiGenerationsUsed() >= sub.getAiGenerationsLimit()) {
            throw new QuotaExceededException(
                    "Monthly AI generation limit reached (" + sub.getAiGenerationsLimit() + "). " +
                    "Upgrade your plan or wait for next billing cycle.");
        }

        sub.setAiGenerationsUsed(sub.getAiGenerationsUsed() + 1);
        subscriptionRepository.save(sub);
    }

    /**
     * Read-only quota gate: throws if {@code userId} has no active subscription or has
     * hit their limit, but does NOT increment. Used when the actual charge should only
     * land once the AI work succeeds (see {@link #incrementAiUsage}) — e.g. guest
     * wall-detection billed to the shop, so a failed run never costs a credit.
     */
    @Transactional(readOnly = true)
    public void assertAiQuotaAvailable(String userId) {
        Subscription sub = subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new QuotaExceededException(
                        "No active subscription. Subscribe to use AI features."));
        if (sub.getAiGenerationsUsed() >= sub.getAiGenerationsLimit()) {
            throw new QuotaExceededException(
                    "Monthly AI generation limit reached (" + sub.getAiGenerationsLimit() + "). " +
                    "Upgrade your plan or wait for next billing cycle.");
        }
    }

    /**
     * Charges one AI generation to {@code userId} once the work has actually succeeded.
     * Best-effort and idempotent-ish: a missing subscription is a no-op (we never charge
     * an account that can't be billed), and the increment is not limit-gated because the
     * generation already happened. Commits independently of the caller.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementAiUsage(String userId) {
        subscriptionRepository
                .findTopByUserIdAndStatusOrderByCreatedAtDesc(userId, SubscriptionStatus.ACTIVE)
                .ifPresent(sub -> {
                    sub.setAiGenerationsUsed(sub.getAiGenerationsUsed() + 1);
                    subscriptionRepository.save(sub);
                });
    }

    /**
     * Activated by Razorpay webhook: subscription.activated
     */
    @Transactional
    public void activateSubscription(String razorpaySubscriptionId, long chargeAt, long currentEnd) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setCurrentPeriodStart(LocalDateTime.now());
            sub.setCurrentPeriodEnd(epochToLocalDateTime(currentEnd));
            subscriptionRepository.save(sub);
            log.info("Subscription activated: {}", razorpaySubscriptionId);
        });
    }

    /**
     * Activated by Razorpay webhook: subscription.cancelled
     */
    @Transactional
    public void markCancelled(String razorpaySubscriptionId) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            sub.setStatus(SubscriptionStatus.CANCELLED);
            subscriptionRepository.save(sub);
            log.info("Subscription cancelled: {}", razorpaySubscriptionId);
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
            sub.setStatus(SubscriptionStatus.HALTED);
            subscriptionRepository.save(sub);
            log.warn("Subscription halted due to payment failure: {}", razorpaySubscriptionId);
        });
    }

    /**
     * On successful renewal (payment.captured), reset monthly AI usage and update period dates.
     */
    @Transactional
    public void handlePaymentCaptured(String razorpaySubscriptionId, long currentEnd) {
        subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).ifPresent(sub -> {
            sub.setStatus(SubscriptionStatus.ACTIVE);
            sub.setAiGenerationsUsed(0);
            sub.setCurrentPeriodStart(LocalDateTime.now());
            sub.setCurrentPeriodEnd(epochToLocalDateTime(currentEnd));
            subscriptionRepository.save(sub);
            log.info("Subscription renewed, AI usage reset: {}", razorpaySubscriptionId);
        });
    }

    /**
     * Safety net: expire any subscriptions whose period has ended and weren't renewed.
     * Runs daily at 01:00.
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void expireStaleSubscriptions() {
        LocalDateTime now = LocalDateTime.now();
        subscriptionRepository.findAll().stream()
                .filter(s -> s.getStatus() == SubscriptionStatus.ACTIVE
                        && s.getCurrentPeriodEnd() != null
                        && s.getCurrentPeriodEnd().isBefore(now))
                .forEach(s -> {
                    s.setStatus(SubscriptionStatus.EXPIRED);
                    subscriptionRepository.save(s);
                    log.info("Subscription expired: {}", s.getId());
                });
    }

    private String resolveRazorpayPlanId(Plan plan) {
        return switch (plan) {
            case STARTER -> planIdStarter;
            case PROFESSIONAL -> planIdProfessional;
            case BUSINESS -> planIdBusiness;
            case ENTERPRISE -> "";
        };
    }

    private LocalDateTime epochToLocalDateTime(long epochSeconds) {
        return java.time.Instant.ofEpochSecond(epochSeconds)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime();
    }
}
