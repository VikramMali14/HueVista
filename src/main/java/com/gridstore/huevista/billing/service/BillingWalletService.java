package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.BillingWalletSummaryResponse;
import com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.model.BillingWallet;
import com.gridstore.huevista.billing.model.BillingWalletTransaction;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.ProjectCreditPayment;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.BillingWalletRepository;
import com.gridstore.huevista.billing.repository.BillingWalletTransactionRepository;
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
 * The prepaid billing wallet: the retailer adds money via a one-time Razorpay
 * order, then spends it on pay-per-use overage — one extra image (Rs. 50 + GST)
 * or one extra AI auto-mask run (Rs. 25 + GST) — without going through a
 * checkout each time. Direct per-item Razorpay payment remains available
 * alongside (see {@link ImageCreditService}); the wallet is the convenience
 * path.
 *
 * Money-safety rules, same as the other payment services: signatures verified
 * server-side, the order is fetched and matched (purpose + user + amount)
 * before crediting, redemptions are replay-protected by the unique payment-id
 * ledger, and every debit is a single conditional UPDATE so concurrent
 * purchases can't overdraw.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingWalletService {

    private final RazorpayClient razorpayClient;
    private final BillingService billingService;
    private final SubscriptionRepository subscriptionRepository;
    private final BillingWalletRepository walletRepository;
    private final BillingWalletTransactionRepository transactionRepository;
    private final ProjectCreditPaymentRepository paymentRepository;
    private final BillingEmailService billingEmailService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    /** Smallest top-up (default Rs. 100) — keeps card fees sane. */
    @Value("${app.wallet.min-topup-paise:10000}")
    private long minTopUpPaise;

    /** Largest single top-up (default Rs. 1,00,000). */
    @Value("${app.wallet.max-topup-paise:10000000}")
    private long maxTopUpPaise;

    @Value("${app.wallet.currency:INR}")
    private String currency;

    // ── Read ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BillingWalletSummaryResponse getWallet(String userId) {
        BillingWallet wallet = walletRepository.findByUserId(userId).orElse(null);
        return BillingWalletSummaryResponse.from(
                wallet, transactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId));
    }

    // ── Top-up ──────────────────────────────────────────────────────────────

    /** Create a Razorpay order for a wallet top-up the client opens in Checkout. */
    public ProjectCreditOrderResponse createTopUpOrder(String userId, long amountPaise) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        // The wallet only pays for plan overage, so it needs a plan to overage on.
        if (!subscriptionRepository.existsByUserIdAndStatus(userId, SubscriptionStatus.ACTIVE)) {
            throw new QuotaExceededException(
                    "No active subscription. Subscribe to a plan first — the wallet tops up a plan.");
        }
        if (amountPaise < minTopUpPaise || amountPaise > maxTopUpPaise) {
            throw new IllegalArgumentException(
                    "Top-up must be between Rs. " + (minTopUpPaise / 100)
                    + " and Rs. " + (maxTopUpPaise / 100) + ".");
        }
        try {
            JSONObject req = new JSONObject();
            req.put("amount", amountPaise);
            req.put("currency", currency);
            req.put("receipt", "wallet_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", "wallet_topup");
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Wallet top-up order created: user={} order={} amountPaise={}",
                    userId, orderId, amountPaise);

            return ProjectCreditOrderResponse.builder()
                    .orderId(orderId)
                    .amount((int) amountPaise)
                    .currency(currency)
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay wallet top-up order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /** Verify the Checkout signature and, if valid, credit the paid amount to the wallet. */
    @Transactional
    public BillingWalletSummaryResponse verifyTopUp(String userId, VerifyProjectCreditRequest req) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay wallet top-up signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // The amount credited is whatever THE ORDER says (top-ups are variable), so
        // the order must be one of OUR wallet_topup orders for THIS user — otherwise
        // a payment for any other order could be funnelled into a wallet.
        long orderAmount;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            orderAmount = ((Number) order.get("amount")).longValue();
            JSONObject notes = order.get("notes");
            String orderPurpose = notes != null ? notes.optString("purpose", "") : "";
            String orderUserId = notes != null ? notes.optString("userId", "") : "";
            if (orderAmount < minTopUpPaise || orderAmount > maxTopUpPaise
                    || !"wallet_topup".equals(orderPurpose)
                    || !userId.equals(orderUserId)) {
                log.warn("Wallet top-up order mismatch: user={} order={} amount={} purpose={} orderUser={}",
                        userId, req.getOrderId(), orderAmount, orderPurpose, orderUserId);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during wallet top-up verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Replay protection: one verified payment credits the wallet exactly once.
        if (paymentRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            paymentRepository.saveAndFlush(
                    ProjectCreditPayment.of(req.getPaymentId(), req.getOrderId(), userId));
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }

        ensureWallet(userId);
        walletRepository.credit(userId, orderAmount);
        transactionRepository.save(BillingWalletTransaction.builder()
                .userId(userId)
                .amountPaise(orderAmount)
                .type(BillingWalletTransaction.Type.TOPUP)
                .reference(req.getPaymentId())
                .build());
        log.info("Wallet top-up credited: user={} amountPaise={} payment={}",
                userId, orderAmount, req.getPaymentId());
        billingEmailService.sendWalletTopUp(userId, orderAmount);
        return getWalletAfterWrite(userId);
    }

    // ── Spend ───────────────────────────────────────────────────────────────

    /**
     * Pay for ONE extra image (Rs. 50 + 18% GST = Rs. 59) from the wallet balance and
     * credit it to the active subscription. The debit and the credit share one
     * transaction — if the subscription credit fails, the money stays in the wallet.
     */
    @Transactional
    public SubscriptionResponse payForImageCredit(String userId) {
        debitOrThrow(userId, Plan.imageOveragePriceWithTaxInPaise(),
                BillingWalletTransaction.Type.EXTRA_IMAGE);
        return billingService.creditPurchasedImage(userId);
    }

    /**
     * Pay for ONE extra AI auto-mask run (Rs. 25 + 18% GST = Rs. 29.50) from the wallet
     * balance and credit it to the active subscription.
     */
    @Transactional
    public SubscriptionResponse payForAutoMaskCredit(String userId) {
        debitOrThrow(userId, Plan.autoMaskOveragePriceWithTaxInPaise(),
                BillingWalletTransaction.Type.EXTRA_AUTO_MASK);
        return billingService.creditPurchasedAutoMask(userId);
    }

    private void debitOrThrow(String userId, long amountPaise, BillingWalletTransaction.Type type) {
        ensureWallet(userId);
        if (walletRepository.debitIfSufficient(userId, amountPaise) == 0) {
            long balance = walletRepository.findByUserId(userId)
                    .map(BillingWallet::getBalancePaise).orElse(0L);
            throw new QuotaExceededException(
                    "Not enough wallet balance (Rs. " + (balance / 100.0) + " available, Rs. "
                    + (amountPaise / 100.0) + " needed). Top up your wallet or pay directly.");
        }
        transactionRepository.save(BillingWalletTransaction.builder()
                .userId(userId)
                .amountPaise(-amountPaise)
                .type(type)
                .build());
        log.info("Wallet debit: user={} amountPaise={} type={}", userId, amountPaise, type);
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** Get-or-create the wallet row, tolerating a concurrent first-create race. */
    private void ensureWallet(String userId) {
        if (walletRepository.findByUserId(userId).isPresent()) {
            return;
        }
        try {
            walletRepository.saveAndFlush(BillingWallet.builder().userId(userId).build());
        } catch (DataIntegrityViolationException raced) {
            // Another request created it between our check and insert — fine.
        }
    }

    private BillingWalletSummaryResponse getWalletAfterWrite(String userId) {
        BillingWallet wallet = walletRepository.findByUserId(userId).orElse(null);
        return BillingWalletSummaryResponse.from(
                wallet, transactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId));
    }
}
