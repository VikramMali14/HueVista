package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.PointsOrderResponse;
import com.gridstore.huevista.billing.dto.VerifyPointsPurchaseRequest;
import com.gridstore.huevista.billing.model.PaymentFlow;
import com.gridstore.huevista.billing.model.PointsPurchase;
import com.gridstore.huevista.billing.repository.PointsPurchaseRepository;
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
 * Buying points with money: create a Razorpay order for N points, then verify the
 * payment and credit them.
 *
 * Besides a subscription this is the only money path left in the product. Everything a
 * shop can buy — extra images, auto-masks, projects, reopens — is paid for in points, so
 * this is where the rupees-to-points conversion happens and the only place it happens.
 *
 * <p>The COUNT is what the client asks for; the amount is derived from it server-side at
 * {@link PricingService#pointsPricePaise}. A client that could name the amount could name
 * a rupee and take a thousand points, so it never does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsPurchaseService {

    private final RazorpayClient razorpayClient;
    private final PointsPurchaseRepository purchaseRepository;
    private final RewardPointsService pointsService;
    private final PricingService pricingService;
    private final BillingEmailService billingEmailService;
    private final com.gridstore.huevista.auth.repository.UserRepository userRepository;
    private final PaymentAttemptService paymentAttemptService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    private static final String ORDER_PURPOSE = "points_purchase";

    /** Create the Razorpay order the client opens in Checkout. */
    public PointsOrderResponse createOrder(String userId, int points) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        // Refuse an account that could never be credited BEFORE any money moves. Points
        // are retailer-only, and RewardPointsService enforces that at the credit step —
        // which is AFTER the payment has been taken. A painter or customer account that
        // reached this flow therefore paid in full, had the whole verification rolled back
        // by the role check, and was left with no points, no record and no refund path.
        // The same rule, applied one step earlier, costs them nothing.
        requirePointsEligible(userId);
        if (points < pricingService.pointsMinPurchase() || points > pricingService.pointsMaxPurchase()) {
            throw new IllegalArgumentException(
                    "Buy between " + pricingService.pointsMinPurchase() + " and "
                    + pricingService.pointsMaxPurchase() + " points at a time.");
        }
        int amountPaise = pricingService.pointsPricePaise(points);
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", pricingService.currency());
            req.put("receipt", "points_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", ORDER_PURPOSE);
            // The count travels on the order so verify credits what was PAID FOR rather
            // than what the client claims afterwards.
            notes.put("points", String.valueOf(points));
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Points order created: user={} order={} points={} amountPaise={}",
                    userId, orderId, points, amountPaise);
            paymentAttemptService.open(orderId, PaymentFlow.POINTS, userId, amountPaise,
                    pricingService.currency(), String.format("%,d points", points), null);

            return PointsOrderResponse.builder()
                    .orderId(orderId)
                    .points(points)
                    .amount(amountPaise)
                    .currency(pricingService.currency())
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay points order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /**
     * Verify the Checkout signature and credit the points the ORDER was for.
     *
     * Signature first, then the order itself: a valid signature proves the payment belongs
     * to some order on this merchant account, not that it belongs to this one, this user,
     * or this many points. All three are read back from Razorpay rather than trusted.
     */
    @Transactional
    public int verifyAndCredit(String userId, VerifyPointsPurchaseRequest req) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay points signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        int points;
        int amountPaise;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            amountPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            points = notes != null ? notes.optInt("points", 0) : 0;

            // The amount must still be what these points cost. Without it, an order whose
            // notes were somehow crafted could claim more points than were paid for.
            if (!ORDER_PURPOSE.equals(purpose) || !userId.equals(orderUserId) || points <= 0
                    || amountPaise != pricingService.pointsPricePaise(points)) {
                log.warn("Points order mismatch: user={} order={} amount={} purpose={} orderUser={} points={}",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId, points);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during points verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Claim the payment exactly once. The pre-check keeps the common case readable;
        // the unique constraint is the race-safe backstop for two concurrent submits.
        if (purchaseRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            purchaseRepository.saveAndFlush(PointsPurchase.of(
                    req.getPaymentId(), req.getOrderId(), userId, points, amountPaise));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }

        pointsService.creditPurchasedPoints(userId, points, req.getPaymentId());
        billingEmailService.sendPointsPurchased(userId, points, amountPaise,
                pricingService.pointsValidityDays());
        return points;
    }

    /**
     * Only a shop account can hold points, so only a shop account may buy them. Phrased as
     * guidance rather than a bare refusal: a customer who lands here wants a visualisation,
     * and the thing that gets them one is their shop's access code, not a points balance.
     */
    private void requirePointsEligible(String userId) {
        com.gridstore.huevista.auth.model.UserRole role = userRepository.findById(userId)
                .map(com.gridstore.huevista.auth.model.User::getRole)
                .orElse(null);
        if (role != com.gridstore.huevista.auth.model.UserRole.RETAILER) {
            throw new SecurityException(
                    "Points are for paint shops — everything they buy (extra projects, reopens) "
                    + "belongs to a shop account, so there would be nothing to spend them on here.");
        }
    }
}
