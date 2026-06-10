package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.account.dto.CustomerEntitlementResponse;
import com.gridstore.huevista.account.service.CustomerEntitlementService;
import com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.model.ProjectCreditPayment;
import com.gridstore.huevista.billing.repository.ProjectCreditPaymentRepository;
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
 * One-time payment so a customer can buy one extra project beyond their included allowance.
 * Uses the Razorpay Orders API + server-side signature verification (no subscription).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectCreditService {

    private final RazorpayClient razorpayClient;
    private final CustomerEntitlementService entitlementService;
    private final ProjectCreditPaymentRepository paymentRepository;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    @Value("${app.project-credit.amount-paise:4900}")
    private int amountPaise;

    @Value("${app.project-credit.currency:INR}")
    private String currency;

    /** Create a Razorpay order the client opens in Checkout. */
    public ProjectCreditOrderResponse createOrder(String userId) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException(
                    "Online payment is not configured. Please ask your retailer to add a project.");
        }
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", currency);
            req.put("receipt", "projcredit_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", "project_credit");
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Project-credit order created: user={} order={}", userId, orderId);

            return ProjectCreditOrderResponse.builder()
                    .orderId(orderId)
                    .amount(amountPaise)
                    .currency(currency)
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /** Verify the Checkout signature and, if valid, credit one project to the customer. */
    @Transactional
    public CustomerEntitlementResponse verifyAndCredit(String userId, VerifyProjectCreditRequest req) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            boolean valid = Utils.verifyPaymentSignature(options, keySecret);
            if (!valid) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Idempotency / replay protection: a verified Razorpay payment buys exactly ONE
        // project credit. The signature stays valid on every replay, so without this a
        // client could re-POST the same (order, payment, signature) triple and mint
        // unlimited credits from a single payment. The unique paymentId column is the
        // race-safe backstop for two concurrent submits that both pass the pre-check.
        if (paymentRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            paymentRepository.saveAndFlush(
                    ProjectCreditPayment.of(req.getPaymentId(), req.getOrderId(), userId));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }

        log.info("Project-credit payment verified: user={} order={} payment={}",
                userId, req.getOrderId(), req.getPaymentId());
        return entitlementService.creditPurchasedProject(userId);
    }
}
