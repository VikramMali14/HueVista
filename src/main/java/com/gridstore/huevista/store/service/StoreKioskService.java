package com.gridstore.huevista.store.service;

import com.gridstore.huevista.account.dto.GuestRedeemResponse;
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
 * The anonymous kiosk money path: Razorpay order for the link's price →
 * Checkout (UPI / QR) → server-side signature verification → one access code,
 * auto guest-redeemed so the customer lands straight in the studio. The
 * platform keeps the configured base (Rs.50); the excess is the retailer's
 * share, recorded on the payment row that the wallet balance is derived from.
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

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    @Value("${app.store.min-price-paise:5000}")
    private int minPricePaise;

    @Value("${app.store.currency:INR}")
    private String currency;

    private static final String ORDER_PURPOSE = "store_kiosk";

    /** Create a Razorpay order the kiosk opens in Checkout. */
    public StoreOrderResponse createOrder(String slug) {
        StoreLink link = requireLink(slug);
        if (!link.isActive()) {
            throw new IllegalStateException("This store's kiosk is paused right now. Please ask at the counter.");
        }
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured. Please pay at the counter.");
        }
        try {
            JSONObject req = new JSONObject();
            req.put("amount", link.getPricePaise());
            req.put("currency", currency);
            req.put("receipt", "store_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("purpose", ORDER_PURPOSE);
            notes.put("storeLinkId", link.getId());
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Store kiosk order created: slug={} order={} amount={}",
                    slug, orderId, link.getPricePaise());

            return StoreOrderResponse.builder()
                    .orderId(orderId)
                    .amount(link.getPricePaise())
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
        // Note: a link deactivated between order and verify is still honored —
        // the money already moved; the pause only stops NEW orders.

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
        int paidPaise;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            paidPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes != null ? notes.optString("purpose", "") : "";
            String storeLinkId = notes != null ? notes.optString("storeLinkId", "") : "";
            if (!ORDER_PURPOSE.equals(purpose) || !link.getId().equals(storeLinkId) || paidPaise < minPricePaise) {
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
        StorePayment payment = StorePayment.builder()
                .storeLink(link)
                .organization(link.getOrganization())
                .paymentId(req.getPaymentId())
                .orderId(req.getOrderId())
                .amountPaise(paidPaise)
                .platformFeePaise(minPricePaise)
                .retailerSharePaise(paidPaise - minPricePaise)
                .build();
        try {
            payment = paymentRepository.saveAndFlush(payment);
        } catch (DataIntegrityViolationException raceLost) {
            StorePayment winner = paymentRepository.findByPaymentId(req.getPaymentId())
                    .orElseThrow(() -> new IllegalStateException("This payment has already been redeemed."));
            return reissue(link, winner);
        }

        CustomerAccessCode code = accessCodeService.issueForStore(link.getOrganization(), link.getValidDays());
        payment.setAccessCode(code);

        log.info("Store kiosk payment verified: slug={} order={} payment={} amount={} share={}",
                slug, req.getOrderId(), req.getPaymentId(), paidPaise, payment.getRetailerSharePaise());

        GuestRedeemResponse guest = accessCodeService.redeemAsGuest(code.getCode());
        return toResponse(guest, paidPaise);
    }

    /** Same payment seen again: same code, fresh guest token (guest re-entry). */
    private StoreCheckoutResponse reissue(StoreLink link, StorePayment payment) {
        if (payment.getAccessCode() == null || !payment.getStoreLink().getId().equals(link.getId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        log.info("Store kiosk payment replayed: payment={} code re-issued", payment.getPaymentId());
        GuestRedeemResponse guest = accessCodeService.redeemAsGuest(payment.getAccessCode().getCode());
        return toResponse(guest, payment.getAmountPaise());
    }

    private StoreCheckoutResponse toResponse(GuestRedeemResponse guest, int amountPaise) {
        return StoreCheckoutResponse.builder()
                .guestToken(guest.getGuestToken())
                .code(guest.getCode())
                .shopName(guest.getShopName())
                .validDays(guest.getValidDays())
                .expiresAt(guest.getExpiresAt())
                .amountPaise(amountPaise)
                .build();
    }

    private StoreLink requireLink(String slug) {
        return linkRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Store not found"));
    }
}
