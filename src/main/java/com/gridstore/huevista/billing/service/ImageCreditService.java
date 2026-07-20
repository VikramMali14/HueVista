package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.ProjectCreditPayment;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.ProjectCreditPaymentRepository;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.common.exception.QuotaExceededException;
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
 * Pay-per-image overage: once a retailer's monthly image quota is spent, one
 * extra image can be bought at Rs. 50 + 18% GST (Rs. 59) via a one-time Razorpay
 * order. Mirrors {@link ProjectCreditService} (orders API + server-side signature
 * verification + replay-protected redemption), but credits the retailer's ACTIVE
 * subscription with a purchased image credit instead of a customer project.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCreditService {

    private final RazorpayClient razorpayClient;
    private final BillingService billingService;
    private final SubscriptionRepository subscriptionRepository;
    private final ProjectCreditPaymentRepository paymentRepository;
    private final BillingEmailService billingEmailService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    /** Rs. 50 base + 18% GST = Rs. 59. Kept configurable for promos, but the
     *  default must track {@link Plan#IMAGE_OVERAGE_PRICE_PAISE}. */
    @Value("${app.image-credit.amount-paise:5900}")
    private int amountPaise;

    @Value("${app.image-credit.currency:INR}")
    private String currency;

    /** Create a Razorpay order for ONE extra image the client opens in Checkout. */
    public ProjectCreditOrderResponse createOrder(String userId) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        // Overage only makes sense on top of an active plan — without one the right
        // path is subscribing, not paying per image.
        if (!subscriptionRepository.existsByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)) {
            throw new QuotaExceededException(
                    "No active subscription. Subscribe to a plan first — extra images top up a plan.");
        }
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", currency);
            req.put("receipt", "imgcredit_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", "image_credit");
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Image-credit order created: user={} order={}", userId, orderId);

            return ProjectCreditOrderResponse.builder()
                    .orderId(orderId)
                    .amount(amountPaise)
                    .currency(currency)
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay image-credit order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /** Verify the Checkout signature and, if valid, add one image credit to the plan. */
    @Transactional
    public SubscriptionResponse verifyAndCredit(String userId, VerifyProjectCreditRequest req) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay image-credit signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // The signature only proves the payment belongs to *some* order on this
        // merchant account. Confirm it is an image-credit order we created for THIS
        // user, for the configured amount — otherwise a payment for any other
        // (cheaper) order could be redeemed here.
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            int orderAmount = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String orderPurpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            if (orderAmount != amountPaise
                    || !"image_credit".equals(orderPurpose)
                    || !userId.equals(orderUserId)) {
                log.warn("Image-credit order mismatch: user={} order={} amount={} purpose={} orderUser={}",
                        userId, req.getOrderId(), orderAmount, orderPurpose, orderUserId);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during image-credit verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Replay protection: one verified payment buys exactly ONE image credit.
        // The unique paymentId column is the race-safe backstop for two concurrent
        // submits that both pass the pre-check.
        if (paymentRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            paymentRepository.saveAndFlush(
                    ProjectCreditPayment.of(req.getPaymentId(), req.getOrderId(), userId));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }

        log.info("Image-credit payment verified: user={} order={} payment={}",
                userId, req.getOrderId(), req.getPaymentId());
        billingEmailService.sendImageCreditPurchased(userId, amountPaise);
        return billingService.creditPurchasedImage(userId);
    }
}
