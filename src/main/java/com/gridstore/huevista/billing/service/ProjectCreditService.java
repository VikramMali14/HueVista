package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse;
import com.gridstore.huevista.billing.dto.ProjectPurchaseOptionsResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.model.BillingWalletTransaction;
import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.model.ProjectCreditPayment;
import com.gridstore.huevista.billing.repository.ProjectCreditPaymentRepository;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import com.gridstore.huevista.project.service.ProjectAccessService;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one-time payments that buy project access: a NEW project, or another window on
 * one whose validity has run out.
 *
 * Both run on the Razorpay Orders API with server-side signature verification (no
 * subscription involved), and both are priced by {@link PricingService} rather than by a
 * fixed constant — a project costs less while the buyer is subscribed, and reopening one
 * costs a fraction of buying it. The price is read at ORDER time and re-read at VERIFY
 * time from the order itself, so the amount charged and the amount checked can never
 * disagree even if the configured price changes in between.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectCreditService {

    private final RazorpayClient razorpayClient;
    private final ProjectCreditPaymentRepository paymentRepository;
    private final ProjectCreditLedger creditLedger;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;
    private final PricingService pricingService;
    private final BillingEmailService billingEmailService;
    private final BillingWalletService walletService;
    private final RewardPointsService rewardPointsService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    private static final String PURPOSE_CREDIT = "project_credit";
    private static final String PURPOSE_REOPEN = "project_reopen";

    /**
     * What buying costs this account right now, and what it gets them — so the UI can
     * state the price and the terms before anyone opens Checkout, and state the OTHER
     * price too ("Rs. 99 once your plan ends") rather than surprising them later.
     */
    @Transactional(readOnly = true)
    public ProjectPurchaseOptionsResponse getOptions(String userId) {
        boolean subscribed = pricingService.isSubscribed(userId);
        return ProjectPurchaseOptionsResponse.builder()
                .subscribed(subscribed)
                .projectPricePaise(pricingService.projectPricePaise(subscribed))
                .subscribedProjectPricePaise(pricingService.projectSubscribedPricePaise())
                .unsubscribedProjectPricePaise(pricingService.projectUnsubscribedPricePaise())
                .reopenPricePaise(pricingService.projectReopenPricePaise())
                .validDays(pricingService.projectValidDays())
                .currency(pricingService.currency())
                .availableCredits(creditLedger.available(userId))
                .build();
    }

    /** Create a Razorpay order for ONE project, priced for this account. */
    public ProjectCreditOrderResponse createOrder(String userId) {
        int amountPaise = pricingService.projectPricePaise(userId);
        return createOrder(userId, amountPaise, PURPOSE_CREDIT, null, "projcredit_");
    }

    /**
     * Create a Razorpay order to reopen a specific lapsed project. Refused for a project
     * that is still open — paying to extend something that has not run out is money for
     * nothing, and the caller almost certainly has a stale view of it.
     */
    @Transactional(readOnly = true)
    public ProjectCreditOrderResponse createReopenOrder(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
        if (project.isAccessWindowOpen()) {
            throw new IllegalStateException("This project is still open — there's nothing to reopen yet.");
        }
        return createOrder(userId, pricingService.projectReopenPricePaise(),
                PURPOSE_REOPEN, projectId, "projreopen_");
    }

    private ProjectCreditOrderResponse createOrder(String userId, int amountPaise, String purpose,
                                                   String projectId, String receiptPrefix) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "Online payment is not configured. Please ask your retailer to add a project.");
        }
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", pricingService.currency());
            req.put("receipt", receiptPrefix + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", purpose);
            if (projectId != null) {
                notes.put("projectId", projectId);
            }
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Project {} order created: user={} order={} amount={}",
                    purpose, userId, orderId, amountPaise);

            return ProjectCreditOrderResponse.builder()
                    .orderId(orderId)
                    .amount(amountPaise)
                    .currency(pricingService.currency())
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /**
     * Verify the Checkout signature and credit ONE project to the buyer.
     *
     * The credit is a ledger row rather than a counter bump, so the price actually paid
     * and the window it opens travel with it to whichever project it eventually becomes.
     */
    @Transactional
    public ProjectPurchaseOptionsResponse verifyAndCredit(String userId, VerifyProjectCreditRequest req) {
        VerifiedOrder order = verify(userId, req, PURPOSE_CREDIT);
        recordPayment(req, userId, ProjectCreditPayment.Purpose.PROJECT_CREDIT,
                order.amountPaise(), null);

        creditLedger.issue(userId, order.amountPaise(), pricingService.projectValidDays(),
                ProjectCredit.Source.PURCHASE);
        log.info("Project credit purchased: user={} order={} payment={} amount={}",
                userId, req.getOrderId(), req.getPaymentId(), order.amountPaise());
        billingEmailService.sendProjectCreditPurchased(userId, order.amountPaise());
        return getOptions(userId);
    }

    /**
     * Verify the Checkout signature and give the named project another validity window.
     *
     * The project id is read from the ORDER, not from the request: the signature proves
     * the payment is genuine but says nothing about what it was for, and trusting a
     * client-supplied id here would let one Rs. 9 payment reopen any project the payer
     * happened to name.
     */
    @Transactional
    public ProjectReopenResponse verifyAndReopen(String userId, VerifyProjectCreditRequest req) {
        VerifiedOrder order = verify(userId, req, PURPOSE_REOPEN);
        if (order.projectId() == null || order.projectId().isBlank()) {
            log.warn("Reopen order {} carries no projectId", req.getOrderId());
            throw new SecurityException("Payment verification failed.");
        }
        Project project = projectRepository.findByIdAndUserId(order.projectId(), userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + order.projectId()));

        recordPayment(req, userId, ProjectCreditPayment.Purpose.PROJECT_REOPEN,
                order.amountPaise(), project.getId());

        projectAccessService.extendWindow(project, pricingService.projectValidDays());
        projectRepository.save(project);
        log.info("Project reopened: user={} project={} until={} payment={}",
                userId, project.getId(), project.getAccessExpiresAt(), req.getPaymentId());
        billingEmailService.sendProjectCreditPurchased(userId, order.amountPaise());

        return ProjectReopenResponse.builder()
                .projectId(project.getId())
                .accessExpiresAt(project.getAccessExpiresAt())
                .paused(project.getAccessPausedAt() != null)
                .amountPaise(order.amountPaise())
                .daysAdded(pricingService.projectValidDays())
                .build();
    }

    // ── Paid from the wallet (prepaid balance / kiosk reward points) ─────────

    /**
     * Buy one project out of the billing wallet instead of opening Checkout.
     *
     * This is the redemption that gives kiosk points their value to a shop with no plan:
     * a subscribed shop already creates projects freely and spends points on image and
     * auto-mask overage, while a lapsed one has no quota to top up and would otherwise
     * hold points it could never use.
     *
     * The price is read here, server-side, from the same {@link PricingService} that
     * quotes Checkout — the caller never names an amount.
     */
    @Transactional
    public ProjectPurchaseOptionsResponse payWithWallet(String userId) {
        int pricePaise = pricingService.projectPricePaise(userId);
        walletService.spend(userId, pricePaise, BillingWalletTransaction.Type.PROJECT_CREDIT);
        creditLedger.issue(userId, pricePaise, pricingService.projectValidDays(),
                ProjectCredit.Source.WALLET);
        log.info("Project credit bought from wallet: user={} pricePaise={}", userId, pricePaise);
        billingEmailService.sendProjectCreditPurchased(userId, pricePaise);
        return getOptions(userId);
    }

    /**
     * Give a project another validity window, paid from the wallet.
     *
     * Unlike the Checkout path there is no order to read the project id from, so it comes
     * from the request — safe here because the lookup is scoped to the caller
     * ({@code findByIdAndUserId}) and the money is their own balance either way.
     */
    @Transactional
    public ProjectReopenResponse reopenWithWallet(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

        int pricePaise = pricingService.projectReopenPricePaise();
        walletService.spend(userId, pricePaise, BillingWalletTransaction.Type.PROJECT_REOPEN);

        projectAccessService.extendWindow(project, pricingService.projectValidDays());
        projectRepository.save(project);
        log.info("Project reopened from wallet: user={} project={} until={} pricePaise={}",
                userId, project.getId(), project.getAccessExpiresAt(), pricePaise);
        billingEmailService.sendProjectCreditPurchased(userId, pricePaise);

        return ProjectReopenResponse.builder()
                .projectId(project.getId())
                .accessExpiresAt(project.getAccessExpiresAt())
                .paused(project.getAccessPausedAt() != null)
                .amountPaise(pricePaise)
                .daysAdded(pricingService.projectValidDays())
                .build();
    }

    /**
     * Buy one project with reward POINTS, at the point price rather than the rupee one.
     *
     * The point price is flat ({@code app.points.project}) where the cash price is not —
     * a shop pays less for a project while subscribed. Points do not follow that split
     * deliberately: they are a reward for kiosk sales, and making them worth less to a
     * lapsed shop would blunt them exactly where they are meant to help.
     */
    @Transactional
    public ProjectPurchaseOptionsResponse payWithPoints(String userId) {
        int points = pricingService.pointsPriceProject();
        rewardPointsService.spend(userId, points,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT, null);
        // The credit records what it WOULD have cost in cash, so the ledger stays in one
        // unit and a project bought with points is worth the same as any other.
        creditLedger.issue(userId, pricingService.projectPricePaise(userId),
                pricingService.projectValidDays(), ProjectCredit.Source.POINTS);
        log.info("Project credit bought with points: user={} points={}", userId, points);
        return getOptions(userId);
    }

    /** Give a project another validity window, paid in reward points. */
    @Transactional
    public ProjectReopenResponse reopenWithPoints(String userId, String projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));

        int points = pricingService.pointsPriceReopen();
        rewardPointsService.spend(userId, points,
                RewardPointsTransaction.Type.SPENT_ON_PROJECT_REOPEN, project.getId());

        projectAccessService.extendWindow(project, pricingService.projectValidDays());
        projectRepository.save(project);
        log.info("Project reopened with points: user={} project={} until={} points={}",
                userId, project.getId(), project.getAccessExpiresAt(), points);

        return ProjectReopenResponse.builder()
                .projectId(project.getId())
                .accessExpiresAt(project.getAccessExpiresAt())
                .paused(project.getAccessPausedAt() != null)
                .amountPaise(0)
                .pointsSpent(points)
                .daysAdded(pricingService.projectValidDays())
                .build();
    }

    /** The bits of a verified Razorpay order this service acts on. */
    private record VerifiedOrder(int amountPaise, String projectId) {}

    /**
     * Signature check, then order check. The signature only proves the payment belongs to
     * SOME order on this merchant account, so the order itself is fetched and confirmed
     * to be the right purpose, for this user, and for a price we would actually have
     * quoted — otherwise a payment for any other (cheaper) order could be redeemed here.
     */
    private VerifiedOrder verify(String userId, VerifyProjectCreditRequest req, String expectedPurpose) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            int orderAmount = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String orderPurpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            String orderProjectId = notes != null ? notes.optString("projectId", null) : null;

            if (!expectedPurpose.equals(orderPurpose) || !userId.equals(orderUserId)
                    || !isPriceWeQuote(orderAmount, expectedPurpose)) {
                log.warn("Project payment order mismatch: user={} order={} amount={} purpose={} orderUser={}",
                        userId, req.getOrderId(), orderAmount, orderPurpose, orderUserId);
                throw new SecurityException("Payment verification failed.");
            }
            return new VerifiedOrder(orderAmount, orderProjectId);
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }
    }

    /**
     * Is this an amount we would have charged for this purpose?
     *
     * Deliberately checked against the SET of valid prices rather than the one the buyer
     * would be quoted today. A retailer can legitimately order at Rs. 99 while lapsed and
     * complete the payment moments after a renewal lands, and rejecting that would eat a
     * payment the customer genuinely made. Both prices are ours; neither is a discount
     * anyone can pick for themselves at order time, because the order amount is set
     * server-side.
     */
    private boolean isPriceWeQuote(int amountPaise, String purpose) {
        if (PURPOSE_REOPEN.equals(purpose)) {
            return amountPaise == pricingService.projectReopenPricePaise();
        }
        return amountPaise == pricingService.projectSubscribedPricePaise()
                || amountPaise == pricingService.projectUnsubscribedPricePaise();
    }

    /**
     * Claim the payment, exactly once.
     *
     * A verified Razorpay signature stays valid on every replay, so without the unique
     * paymentId a client could re-POST the same (order, payment, signature) triple and
     * mint unlimited credits from one payment. The pre-check keeps the common case
     * readable; the constraint violation is the race-safe backstop for two concurrent
     * submits that both pass it.
     */
    private void recordPayment(VerifyProjectCreditRequest req, String userId,
                               ProjectCreditPayment.Purpose purpose, int amountPaise, String projectId) {
        if (paymentRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            paymentRepository.saveAndFlush(ProjectCreditPayment.of(
                    req.getPaymentId(), req.getOrderId(), userId, purpose, amountPaise, projectId));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
    }
}
