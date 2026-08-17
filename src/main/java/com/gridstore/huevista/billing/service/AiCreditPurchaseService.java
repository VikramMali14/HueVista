package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.AiCreditOrderResponse;
import com.gridstore.huevista.billing.dto.VerifyAiCreditPurchaseRequest;
import com.gridstore.huevista.billing.model.AiCreditPurchase;
import com.gridstore.huevista.billing.model.PaymentFlow;
import com.gridstore.huevista.billing.repository.AiCreditPurchaseRepository;
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
 * Buying AI image credits with money: create a Razorpay order for N credits, then verify
 * the payment and credit the wallet.
 *
 * <p>The COUNT is what the client asks for; the amount is derived from it server-side at
 * {@link PricingService#aiCreditPricePaise(int)}. A client that could name the amount could
 * name a rupee and take fifty images, so it never does — and the discount is re-derived at
 * verification time from the rate the ORDER was opened at, not from the one in force when
 * the buyer comes back, so a launch offer that ends mid-checkout neither overcharges the
 * buyer nor lets a stale order claim a discount that has gone.
 *
 * <p>Modelled closely on {@link PointsPurchaseService} because the two are the same
 * transaction with a different unit, and a second shape here would be a second set of
 * replay and amount-verification bugs to find.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCreditPurchaseService {

    private final RazorpayClient razorpayClient;
    private final AiCreditPurchaseRepository purchaseRepository;
    private final AiCreditService aiCreditService;
    private final PricingService pricingService;
    private final PaymentAttemptService paymentAttemptService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    private static final String ORDER_PURPOSE = "ai_credit_purchase";

    /** Create the Razorpay order the client opens in Checkout. */
    public AiCreditOrderResponse createOrder(String userId, int credits) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        // Refuse an account that could never be credited BEFORE any money moves. The role
        // check also lives in AiCreditService, but that one runs at the CREDIT step, which
        // is after the payment has been taken — a painter reaching this flow would have
        // paid in full, had the whole verification rolled back by the role check, and been
        // left with no credits, no record and no refund path. The same rule one step
        // earlier costs them nothing.
        requireEligible(userId);
        int min = pricingService.aiCreditMinPurchase();
        int max = pricingService.aiCreditMaxPurchase();
        if (credits < min || credits > max) {
            throw new IllegalArgumentException(
                    "Buy between " + min + " and " + max + " AI image credits at a time.");
        }

        // The buyer's own rate: a CUSTOMER buys off the catalogue, a shop at the shop
        // price. Both travel onto the order below, so verification checks the amount
        // against what this buyer was actually quoted rather than against a rate that may
        // have moved — or against a different rate belonging to a different kind of buyer.
        int amountPaise = pricingService.aiCreditPricePaise(userId, credits);
        int discountPercent = pricingService.aiCreditDiscountPercent(userId);
        int listPricePaise = pricingService.aiCreditListPricePaise(userId);
        if (amountPaise <= 0) {
            // A 100% discount would open a zero-rupee order, which Razorpay refuses with a
            // message nobody can act on. Say the true thing instead.
            throw new IllegalStateException("AI image credits aren't on sale right now.");
        }

        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", pricingService.currency());
            req.put("receipt", "aicredits_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", ORDER_PURPOSE);
            // The count and the rate both travel on the order, so verify recomputes the
            // expected amount from what the order was actually opened at rather than from
            // what the client sends back or from a price that has since moved.
            notes.put("credits", credits);
            notes.put("listPricePaise", listPricePaise);
            notes.put("discountPercent", discountPercent);
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("AI credit order created: user={} order={} credits={} amountPaise={} discount={}%",
                    userId, orderId, credits, amountPaise, discountPercent);
            // Opened here, while the buyer's request is still on the thread — it is the only
            // moment we can see their IP, browser and originating page.
            paymentAttemptService.open(orderId, PaymentFlow.AI_CREDITS, userId, amountPaise,
                    pricingService.currency(),
                    credits + (credits == 1 ? " AI image credit" : " AI image credits"), null);

            return AiCreditOrderResponse.builder()
                    .orderId(orderId)
                    .credits(credits)
                    .amount(amountPaise)
                    .listAmount(listPricePaise * credits)
                    .discountPercent(discountPercent)
                    .currency(pricingService.currency())
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay AI credit order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /**
     * Verify the Checkout signature and credit the credits the ORDER was for.
     *
     * Signature first, then the order itself: a valid signature proves the payment belongs
     * to some order on this merchant account, not that it belongs to this one, this user,
     * or this many credits. All three are read back from Razorpay rather than trusted.
     *
     * @return the wallet balance after crediting
     */
    @Transactional
    public int verifyAndCredit(String userId, VerifyAiCreditPurchaseRequest req) {
        verifySignature(req);

        int credits;
        int amountPaise;
        int listPricePaise;
        int discountPercent;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            amountPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            credits = notes != null ? notes.optInt("credits", 0) : 0;
            listPricePaise = notes != null ? notes.optInt("listPricePaise", 0) : 0;
            discountPercent = notes != null ? notes.optInt("discountPercent", 0) : 0;

            // The amount must still be what those credits cost AT THE RATE THE ORDER NAMES.
            // Re-reading the live price here instead would break every order in flight the
            // moment the launch discount ends — a buyer who correctly paid ₹99 would come
            // back to a ₹198 expectation and fail verification on money they had handed over.
            if (!ORDER_PURPOSE.equals(purpose) || !userId.equals(orderUserId) || credits <= 0
                    || amountPaise != expectedAmount(listPricePaise, discountPercent, credits)) {
                log.warn("AI credit order mismatch: user={} order={} amount={} purpose={} "
                         + "orderUser={} credits={} list={} discount={}",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId, credits,
                        listPricePaise, discountPercent);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during AI credit verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Claim the payment exactly once. The pre-check keeps the common case readable;
        // the unique constraint is the race-safe backstop for two concurrent submits.
        if (purchaseRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            purchaseRepository.saveAndFlush(AiCreditPurchase.of(
                    req.getPaymentId(), req.getOrderId(), userId, credits,
                    listPricePaise, discountPercent, amountPaise));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }

        return aiCreditService.creditPurchased(userId, credits, req.getPaymentId(),
                pricingService.aiCreditValidityDays(userId));
    }

    /**
     * What an order for {@code credits} at the rate it was opened at must have charged.
     *
     * Deliberately mirrors {@link PricingService#aiCreditPricePaise(int)} — discount applied
     * to the whole order, not per credit — because a different rounding here would reject a
     * correctly-paid multi-credit purchase by a rupee.
     */
    private static int expectedAmount(int listPricePaise, int discountPercent, int credits) {
        if (listPricePaise <= 0 || discountPercent < 0 || discountPercent > 100) {
            return Integer.MIN_VALUE; // an unrecognised rate has no price, so nothing matches
        }
        long gross = (long) listPricePaise * credits;
        return (int) (gross * (100 - discountPercent) / 100);
    }

    /** The Checkout signature must belong to this merchant account. */
    private void verifySignature(VerifyAiCreditPurchaseRequest req) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay AI credit signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }
    }

    private void requireEligible(String userId) {
        if (!aiCreditService.isEligible(userId)) {
            throw new SecurityException(
                    "AI image credits belong to the account that owns the room — a shop, or a "
                    + "customer working on the project their shop gave them. There would be "
                    + "nothing to spend them on here.");
        }
    }
}
