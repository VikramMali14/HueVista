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
 * The prepaid billing wallet — also the shop's reward-point balance, because the two are
 * deliberately the same pot of spending power.
 *
 * Balance arrives two ways: the retailer tops up through a one-time Razorpay order, or
 * earns points because a walk-in bought a visualisation through their kiosk link
 * ({@link #creditKioskBonus}). Both are spent the same way — extra images, extra
 * auto-masks, whole projects, project reopens — so a shop is never left holding a
 * balance with nothing to buy. Direct per-item Razorpay payment remains available
 * alongside (see {@link ImageCreditService}); the wallet is the convenience path.
 *
 * <p><b>The balance never leaves as cash.</b> There is no payout path here, and there
 * must not be one: points are earned on money HueVista collected for its own service,
 * and converting them to a bank transfer would turn every kiosk sale into a collection
 * made on the shop's behalf — a regulated pattern the flat-price kiosk exists to avoid.
 * The one exception is {@link #refundWallet}, which an ADMIN uses to return money a
 * retailer actually paid in, and which is manual on purpose.
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

    // ── Kiosk reward points ─────────────────────────────────────────────────

    /**
     * Credit the points a shop earned because a walk-in paid at its kiosk.
     *
     * Not replay-protected here on purpose: the caller
     * ({@link com.gridstore.huevista.store.service.StoreKioskService}) only reaches this
     * after winning the unique-paymentId insert on the kiosk payment row, so one payment
     * can only ever get this far once. Doing it again here would mean two competing
     * claims on the same fact.
     *
     * <p>Points do NOT require a subscription — an unsubscribed shop can still earn, and
     * spends them on projects rather than on plan overage. Gating the credit on a plan
     * would strand exactly the shops the kiosk is meant to convert.
     */
    @Transactional
    public void creditKioskBonus(String userId, long pointsPaise, String reference) {
        if (pointsPaise <= 0) {
            return;
        }
        ensureWallet(userId);
        walletRepository.credit(userId, pointsPaise);
        transactionRepository.save(BillingWalletTransaction.builder()
                .userId(userId)
                .amountPaise(pointsPaise)
                .type(BillingWalletTransaction.Type.KIOSK_BONUS)
                .reference(reference)
                .build());
        log.info("Kiosk bonus points credited: user={} points={} payment={}",
                userId, pointsPaise, reference);
    }

    /**
     * Take back the points from a kiosk payment that was refunded.
     *
     * Debits even when the balance no longer covers it — see
     * {@link com.gridstore.huevista.billing.repository.BillingWalletRepository#debitAllowingNegative}.
     * A shop that spent the points before the refund landed goes negative and earns its
     * way back, which is the honest outcome; the alternative is a refunded sale that
     * still paid out.
     */
    @Transactional
    public void reverseKioskBonus(String userId, long pointsPaise, String reference) {
        if (pointsPaise <= 0) {
            return;
        }
        ensureWallet(userId);
        walletRepository.debitAllowingNegative(userId, pointsPaise);
        transactionRepository.save(BillingWalletTransaction.builder()
                .userId(userId)
                .amountPaise(-pointsPaise)
                .type(BillingWalletTransaction.Type.KIOSK_BONUS_REVERSAL)
                .reference(reference)
                .build());
        long balance = balancePaise(userId);
        if (balance < 0) {
            log.warn("Kiosk bonus reversed into a negative balance: user={} points={} balanceNow={} "
                    + "payment={} — the points were spent before the refund arrived; it settles "
                    + "against future earnings.", userId, pointsPaise, balance, reference);
        } else {
            log.info("Kiosk bonus points reversed: user={} points={} payment={}",
                    userId, pointsPaise, reference);
        }
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

    /**
     * ADMIN: refund a prepaid billing-wallet balance out of the platform (the actual money
     * movement is manual, like the kiosk payouts) and zero the wallet.
     *
     * Needed because wallet money was otherwise a one-way street: top-ups require an
     * active plan and so does spending, so the moment a retailer cancelled — or deleted
     * their account — whatever they had prepaid became both unspendable and
     * unrecoverable. Returns the amount that was written off so the admin knows what to
     * transfer.
     */
    @Transactional
    public long refundWallet(String adminUserId, String userId, String reason) {
        BillingWallet wallet = walletRepository.findByUserId(userId).orElse(null);
        long balance = wallet != null ? wallet.getBalancePaise() : 0L;
        if (balance <= 0) {
            return 0L;
        }
        if (walletRepository.debitIfSufficient(userId, balance) == 0) {
            // Lost a race with a concurrent spend — the caller can retry against the
            // now-smaller balance rather than us guessing at a partial refund.
            throw new IllegalStateException("The wallet balance changed — retry the refund.");
        }
        transactionRepository.save(BillingWalletTransaction.builder()
                .userId(userId)
                .amountPaise(-balance)
                .type(BillingWalletTransaction.Type.REFUND)
                .reference(reason != null && !reason.isBlank() ? reason.trim() : "admin refund")
                .build());
        log.warn("Billing wallet refunded by admin {}: user={} amountPaise={} reason={}",
                adminUserId, userId, balance, reason);
        return balance;
    }

    /** The prepaid balance sitting on an account, in paise (0 when there is no wallet). */
    @Transactional(readOnly = true)
    public long balancePaise(String userId) {
        return walletRepository.findByUserId(userId).map(BillingWallet::getBalancePaise).orElse(0L);
    }

    /**
     * Spend from the balance for a purchase this service does not itself own.
     *
     * The image and auto-mask paths above debit and credit in one method because the
     * thing being bought is a counter on the subscription. Projects are not: their
     * ledger, validity window and ownership checks live in
     * {@link ProjectCreditService}, so that service drives the purchase and calls here
     * for the money. Runs in the CALLER's transaction — a failure after the debit rolls
     * the money back with it.
     */
    @Transactional
    public void spend(String userId, long amountPaise, BillingWalletTransaction.Type type) {
        debitOrThrow(userId, amountPaise, type);
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
