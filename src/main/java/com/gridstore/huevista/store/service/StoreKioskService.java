package com.gridstore.huevista.store.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.service.AccessCodeService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.store.dto.StoreCheckoutResponse;
import com.gridstore.huevista.store.dto.StoreOrderResponse;
import com.gridstore.huevista.store.dto.VerifyStoreOrderRequest;
import com.gridstore.huevista.store.model.StoreLink;
import com.gridstore.huevista.store.model.StorePayment;
import com.gridstore.huevista.store.repository.StoreLinkRepository;
import com.gridstore.huevista.store.repository.StorePaymentRepository;
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
 * The anonymous kiosk money path: Razorpay order for the platform kiosk price →
 * Checkout (UPI / QR) → server-side signature verification → one access code,
 * auto guest-redeemed so the customer lands straight in the studio.
 *
 * <p><b>The whole payment is HueVista's.</b> The walk-in is buying a HueVista
 * visualisation at one flat platform-wide price; the shop neither sets that price nor
 * takes a share of it. What the shop gets is reward POINTS credited to its owner's point
 * ledger ({@code app.store.bonus-points}), spendable only on HueVista services, expiring
 * a year after they are earned, and never withdrawable.
 *
 * That split is the point of the design, not an accounting detail. Letting the shop
 * price the link and keep the excess made every kiosk sale a payment collected on a
 * third party's behalf and settled out by manual bank transfer — a regulated pattern
 * that needs Razorpay Route and would not survive an activation review. Points keep the
 * shop's incentive while leaving the money flow a plain B2C sale. Do not reintroduce a
 * retailer share or a cash-out path here.
 *
 * Mirrors {@link com.gridstore.huevista.billing.service.ProjectCreditService}
 * with one deliberate difference: replaying an already-redeemed payment is NOT
 * an error here — it returns the SAME code with a fresh guest token. A kiosk
 * customer whose network dropped mid-verify must never lose what they paid for,
 * and the (order, payment, signature) triple only exists in the payer's browser.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreKioskService {

    private final RazorpayClient razorpayClient;
    private final StoreLinkRepository linkRepository;
    private final StorePaymentRepository paymentRepository;
    private final AccessCodeService accessCodeService;
    private final com.gridstore.huevista.billing.service.PricingService pricingService;
    private final com.gridstore.huevista.billing.service.RewardPointsService rewardPointsService;
    private final com.gridstore.huevista.billing.service.PaymentAttemptService paymentAttemptService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    @Value("${app.store.currency:INR}")
    private String currency;

    private static final String ORDER_PURPOSE = "store_kiosk";

    /** Create a Razorpay order the kiosk opens in Checkout. */
    public StoreOrderResponse createOrder(String slug) {
        StoreLink link = requireLiveLink(slug);
        if (!link.isActive()) {
            throw new IllegalStateException("This store's kiosk is paused right now. Please ask at the counter.");
        }
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured. Please pay at the counter.");
        }
        // Priced here, not from the link row: the price is a platform decision and a link
        // created before it last changed must not keep charging the old amount.
        int chargePaise = pricingService.kioskPricePaise();
        try {
            JSONObject req = new JSONObject();
            req.put("amount", chargePaise);
            req.put("currency", currency);
            req.put("receipt", "store_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("purpose", ORDER_PURPOSE);
            notes.put("storeLinkId", link.getId());
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Store kiosk order created: slug={} order={} amount={}",
                    slug, orderId, chargePaise);
            // No buyer id to record — a kiosk customer is a walk-in with no account — so
            // the attempt is attributed to the SHOP instead. Which is the more useful
            // attribution anyway: a kiosk abandoning every sale is a broken counter, and
            // this is the only place that would show it.
            paymentAttemptService.open(orderId,
                    com.gridstore.huevista.billing.model.PaymentFlow.STORE_KIOSK, null, chargePaise,
                    currency, "One room visualisation · " + link.getOrganization().getName(), null);
            paymentAttemptService.attachOrganization(orderId, link.getOrganization().getId());

            return StoreOrderResponse.builder()
                    .orderId(orderId)
                    .amount(chargePaise)
                    .currency(currency)
                    .razorpayKeyId(keyId)
                    .shopName(link.getOrganization().getName())
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay store order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /**
     * Verify the Checkout signature and, if valid, issue the paid-for access code
     * and open the guest session. Idempotent per payment: a replay of the same
     * verified triple re-issues a token for the SAME code (guest re-entry).
     */
    @Transactional
    public StoreCheckoutResponse verifyAndIssue(String slug, VerifyStoreOrderRequest req) {
        StoreLink link = requireLink(slug);
        // Note: a link deactivated OR deleted between order and verify is still
        // honoured — the money already moved; both only stop NEW orders.

        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay store signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Network-retry replay: hand back the code this payment already bought.
        StorePayment existing = paymentRepository.findByPaymentId(req.getPaymentId()).orElse(null);
        if (existing != null) {
            return reissue(link, existing);
        }

        // The signature only proves the payment belongs to *some* order on this
        // merchant account. Fetch the order and confirm it is a kiosk order for
        // THIS store link — otherwise a payment for any other (cheaper) order
        // could be redeemed here — and read the authoritative paid amount from it.
        //
        // The amount itself is NOT required to equal today's price. It is set server-side
        // at order time and bound to this link by the notes, so there is nothing for a
        // payer to choose; demanding an exact match would instead reject a customer who
        // opened Checkout moments before the platform price changed and take their money
        // for nothing.
        int paidPaise;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            paidPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes != null ? notes.optString("purpose", "") : "";
            String storeLinkId = notes != null ? notes.optString("storeLinkId", "") : "";
            if (!ORDER_PURPOSE.equals(purpose) || !link.getId().equals(storeLinkId) || paidPaise <= 0) {
                log.warn("Store order mismatch: slug={} order={} amount={} purpose={} linkId={}",
                        slug, req.getOrderId(), paidPaise, purpose, storeLinkId);
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during store verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Claim the payment FIRST (unique paymentId is the race-safe backstop for
        // two concurrent submits), then issue the code — so a lost race never
        // leaves an orphaned unpaid code behind.
        //
        // The cash is entirely ours; the shop's reward is points, awarded on top rather
        // than carved out of the payment.
        int bonusPoints = pricingService.kioskBonusPoints();
        StorePayment payment = StorePayment.builder()
                .storeLink(link)
                .organization(link.getOrganization())
                .paymentId(req.getPaymentId())
                .orderId(req.getOrderId())
                .amountPaise(paidPaise)
                .platformFeePaise(paidPaise)
                .bonusPoints(bonusPoints)
                .build();
        try {
            payment = paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException raceLost) {
            StorePayment winner = paymentRepository.findByPaymentId(req.getPaymentId())
                    .orElseThrow(() -> new IllegalStateException("This payment has already been redeemed."));
            return reissue(link, winner);
        }

        CustomerAccessCode code = accessCodeService.issueForStore(link.getOrganization());
        payment.setAccessCode(code);

        // Reward the shop whose link made the sale. Shares this transaction with the
        // payment row, so points and the record of why they exist land together or not at
        // all. A shop with no owner account earns nothing and the sale still completes —
        // the walk-in has paid, and their access must not hinge on the shop's setup.
        String ownerUserId = pricingService.shopOwnerUserId(link.getOrganization().getId()).orElse(null);
        if (ownerUserId != null) {
            rewardPointsService.creditKioskPoints(ownerUserId, bonusPoints, req.getPaymentId());
        } else {
            log.warn("Kiosk sale earned no points: org={} has no owner account (payment={})",
                    link.getOrganization().getId(), req.getPaymentId());
        }

        log.info("Store kiosk payment verified: slug={} order={} payment={} amount={} points={}",
                slug, req.getOrderId(), req.getPaymentId(), paidPaise, bonusPoints);

        return toResponse(code, paidPaise);
    }

    /** Same payment seen again: hand back the same code. */
    private StoreCheckoutResponse reissue(StoreLink link, StorePayment payment) {
        if (payment.getAccessCode() == null || !payment.getStoreLink().getId().equals(link.getId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        // A replayed payment shows the buyer their code again — for the customer whose
        // network dropped mid-verify. Once the code is REDEEMED it is theirs on their
        // account, and isExpired() is false forever after, so this only ever refuses a
        // code nobody claimed inside its 30 days.
        if (payment.getAccessCode().isExpired()) {
            throw new IllegalStateException(
                    "Nobody redeemed the code this payment bought within 30 days. "
                    + "Please ask at the counter.");
        }
        if (payment.isReversed()) {
            throw new IllegalStateException("This payment was refunded.");
        }
        log.info("Store kiosk payment replayed: payment={} code re-issued", payment.getPaymentId());
        return toResponse(payment.getAccessCode(), payment.getAmountPaise());
    }

    private StoreCheckoutResponse toResponse(CustomerAccessCode code, int amountPaise) {
        return StoreCheckoutResponse.builder()
                .code(code.getCode())
                .shopName(code.getOrganization().getName())
                .validDays(daysLeftToRedeem(code))
                .expiresAt(code.getExpiresAt()
                        .atZone(java.time.ZoneId.systemDefault()).toInstant())
                .amountPaise(amountPaise)
                .build();
    }

    /** Whole days left to redeem, floored at zero so a receipt never shows a negative. */
    private static int daysLeftToRedeem(CustomerAccessCode code) {
        long days = java.time.Duration
                .between(java.time.LocalDateTime.now(), code.getExpiresAt()).toDays();
        return (int) Math.max(0, days);
    }

    /**
     * A link the kiosk will serve — not one the shop has deleted. Use for anything
     * that STARTS a purchase.
     */
    private StoreLink requireLiveLink(String slug) {
        return linkRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
    }

    /**
     * A link by slug, deleted or not — only for finishing a payment already in flight.
     *
     * The same asymmetry a pause already has: a pause stops new orders and still honours
     * one that is mid-Checkout, because the money has moved. A deletion is a harder
     * stop, but not hard enough to keep a walk-in's money and give them nothing — if
     * the shop retires the link while a Checkout is open, that customer still gets the
     * code they paid for.
     */
    private StoreLink requireLink(String slug) {
        return linkRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
    }
}
