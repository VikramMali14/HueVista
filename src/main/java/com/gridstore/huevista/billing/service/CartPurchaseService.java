package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.dto.CartCatalogueResponse;
import com.gridstore.huevista.billing.dto.CartOrderResponse;
import com.gridstore.huevista.billing.dto.CreateCartOrderRequest;
import com.gridstore.huevista.billing.dto.VerifyCartPurchaseRequest;
import com.gridstore.huevista.billing.model.CartPurchase;
import com.gridstore.huevista.billing.model.PaymentFlow;
import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.repository.CartPurchaseRepository;
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

import java.util.ArrayList;
import java.util.List;

/**
 * The customer's basket: several projects, several AI image credits, the combo of the two,
 * or the special-offer bundle, bought in one payment with one offer applied to the lot.
 *
 * <p><b>Why a basket at all.</b> Everything the product sold a customer before this was a
 * single item behind a single button — one project, or one credit, each with its own
 * payment sheet. That is the wrong shape for the person it is aimed at: somebody doing up
 * two rooms wants two projects and four pictures, and the old flow made them open Checkout
 * six times and paid no attention to the fact that they had. The cart makes the size of the
 * order visible to both sides — which is what an offer at ₹289 is for.
 *
 * <p><b>The client names quantities. Never money.</b> Every price is read off
 * {@link PricingService} here, the subtotal is added up here, and the discount is re-derived
 * from the subtotal here — a code that has not been earned takes nothing off, however it is
 * sent. The rates and quantities then travel ON the Razorpay order, so verification checks
 * the amount against what the order was actually opened at rather than against a catalogue
 * that may have moved in between. That is the same handshake
 * {@link AiCreditPurchaseService} uses, and for the same reason: an offer that ends
 * mid-checkout must neither overcharge the buyer nor let a stale order claim a rate that
 * has gone.
 *
 * <p><b>The percentage offers price the à-la-carte half of the basket, and only that.</b>
 * The combo and the special-offer bundle are packages: the saving is already in the price
 * on the ticket, and stacking HUE25 on "three for the price of two" discounts one basket
 * twice at a rate nobody set. So the offer is earned on, and taken off, the single
 * projects and single credits in the basket — {@link Quote#discountBasePaise()} — while the
 * packages are rung up at the price they are advertised at. It is a switch
 * ({@code app.customer-catalogue.offers-apply-to-packages}) rather than a rule welded in,
 * because it is a commercial decision; and the base the discount was struck on travels ON
 * the Razorpay order, so flipping it never disturbs an order already open in Checkout.
 *
 * <p><b>CUSTOMER only, for now.</b> A shop's prices move with its plan and its projects land
 * on that plan's allowance; putting a shop through this counter would quote it retail for
 * things it buys at a tier rate. It is refused before any money moves rather than after.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartPurchaseService {

    private final RazorpayClient razorpayClient;
    private final CartPurchaseRepository purchaseRepository;
    private final ProjectCreditService projectCreditService;
    private final AiCreditService aiCreditService;
    private final PricingService pricingService;
    private final PaymentAttemptService paymentAttemptService;

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    private static final String ORDER_PURPOSE = "cart_purchase";

    // ── The counter ─────────────────────────────────────────────────────────

    /** What is for sale, what it costs, and what this account already holds. */
    @Transactional(readOnly = true)
    public CartCatalogueResponse catalogue(String userId) {
        boolean eligible = pricingService.buysFromCatalogue(userId);
        return CartCatalogueResponse.builder()
                .eligible(eligible)
                .projectPricePaise(pricingService.cataloguePricePerProject())
                .creditPricePaise(pricingService.cataloguePricePerCredit())
                .comboPricePaise(pricingService.cataloguePricePerCombo())
                .comboProjects(pricingService.catalogueComboProjects())
                .comboCredits(pricingService.catalogueComboCredits())
                .bundleAvailable(pricingService.catalogueBundleAvailable())
                .bundlePricePaise(pricingService.cataloguePricePerBundle())
                .bundleListPricePaise(pricingService.catalogueBundleListPricePaise())
                .bundleProjects(pricingService.catalogueBundleProjects())
                .bundleCredits(pricingService.catalogueBundleCredits())
                .validDays(pricingService.catalogueValidityDays())
                .maxQuantity(pricingService.catalogueMaxQuantity())
                .offers(pricingService.catalogueOffers().stream()
                        .map(o -> CartCatalogueResponse.Offer.builder()
                                .code(o.code())
                                .minSubtotalPaise(o.minSubtotalPaise())
                                .percentOff(o.percentOff())
                                .build())
                        .toList())
                .offersApplyToPackages(pricingService.catalogueOffersApplyToPackages())
                .availableProjects(projectCreditService.availableCredits(userId))
                .creditBalance(aiCreditService.balance(userId))
                .creditsExpireAt(aiCreditService.soonestExpiry(userId).orElse(null))
                .creditsExpiring(aiCreditService.creditsExpiringSoonest(userId))
                .currency(pricingService.currency())
                .build();
    }

    // ── Pricing one basket ──────────────────────────────────────────────────

    /**
     * A priced basket. Every number the buyer is shown and every number the order is
     * opened at, worked out in one place so the quote and the charge cannot drift.
     */
    public record Quote(int projectQty, int creditQty, int comboQty, int bundleQty,
                        int projectPricePaise, int creditPricePaise, int comboPricePaise,
                        int bundlePricePaise,
                        int comboProjects, int comboCredits,
                        int bundleProjects, int bundleCredits,
                        int subtotalPaise, int discountBasePaise,
                        String discountCode, int discountPercent,
                        int discountPaise, int amountPaise,
                        int projectsGranted, int creditsGranted, int validDays) {}

    /**
     * Price what the client asked for, at today's rates.
     *
     * <p>The quantity checks come first and are deliberately strict. A quantity is the one
     * input the server multiplies by a price, so it is exactly the input a client should not
     * be able to invent: an empty basket, a negative line and a thousand projects are all
     * refused here rather than turned into an order somebody has to work out afterwards.
     */
    private Quote quote(CreateCartOrderRequest request) {
        int max = pricingService.catalogueMaxQuantity();
        int projectQty = requireQuantity(request.getProjects(), max, "projects");
        int creditQty = requireQuantity(request.getCredits(), max, "AI image credits");
        int comboQty = requireQuantity(request.getCombos(), max, "combos");
        int bundleQty = requireQuantity(request.getBundles(), max, "offer bundles");
        if (projectQty + creditQty + comboQty + bundleQty == 0) {
            throw new IllegalArgumentException("Your basket is empty.");
        }
        // An offer that has been switched off is refused rather than quietly rung up at
        // whatever the parts happen to come to — that would charge full price for a line
        // the buyer only picked because it said "three for the price of two".
        if (bundleQty > 0 && !pricingService.catalogueBundleAvailable()) {
            throw new IllegalArgumentException("That offer has ended.");
        }

        int projectPrice = pricingService.cataloguePricePerProject();
        int creditPrice = pricingService.cataloguePricePerCredit();
        int comboPrice = pricingService.cataloguePricePerCombo();
        int bundlePrice = pricingService.cataloguePricePerBundle();
        int comboProjects = pricingService.catalogueComboProjects();
        int comboCredits = pricingService.catalogueComboCredits();
        int bundleProjects = pricingService.catalogueBundleProjects();
        int bundleCredits = pricingService.catalogueBundleCredits();

        int singlesSubtotal = projectQty * projectPrice + creditQty * creditPrice;
        int packagesSubtotal = comboQty * comboPrice + bundleQty * bundlePrice;
        int subtotal = singlesSubtotal + packagesSubtotal;

        // What the offer is measured against AND taken off — the same number for both, so
        // there is no basket that earns a discount of nothing. The packages are excluded
        // from it: their saving is already in their price. See the class note.
        int discountBase = pricingService.catalogueOffersApplyToPackages()
                ? subtotal : singlesSubtotal;

        // The offer the buyer asked for if this basket has earned it, and the best one it
        // has earned otherwise. Never worse than what they picked, and never better than
        // what the base deserves — the code is a preference, not a claim.
        final int base = discountBase;
        PricingService.CatalogueOffer offer = pricingService
                .offerFor(request.getDiscountCode(), base)
                .or(() -> pricingService.bestOfferFor(base))
                .orElse(null);
        int percent = offer == null ? 0 : offer.percentOff();
        int discount = PricingService.discountPaise(discountBase, percent);

        return new Quote(projectQty, creditQty, comboQty, bundleQty,
                projectPrice, creditPrice, comboPrice, bundlePrice,
                comboProjects, comboCredits, bundleProjects, bundleCredits,
                subtotal, discountBase, offer == null ? null : offer.code(), percent, discount,
                subtotal - discount,
                projectQty + comboQty * comboProjects + bundleQty * bundleProjects,
                creditQty + comboQty * comboCredits + bundleQty * bundleCredits,
                pricingService.catalogueValidityDays());
    }

    private static int requireQuantity(int quantity, int max, String what) {
        if (quantity < 0) {
            throw new IllegalArgumentException("A quantity cannot be negative.");
        }
        if (quantity > max) {
            throw new IllegalArgumentException(
                    "You can buy up to " + max + " " + what + " in one order.");
        }
        return quantity;
    }

    // ── Buying ──────────────────────────────────────────────────────────────

    /** Create the Razorpay order the client opens in Checkout. */
    public CartOrderResponse createOrder(String userId, CreateCartOrderRequest request) {
        if (keyId.isBlank() || keySecret.isBlank()) {
            throw new IllegalStateException("Online payment is not configured.");
        }
        requireEligible(userId);
        Quote quote = quote(request);
        if (quote.amountPaise() <= 0) {
            // A misconfigured 100% offer would open a zero-rupee order, which Razorpay
            // refuses with a message nobody can act on. Say the true thing instead.
            throw new IllegalStateException("That basket isn't on sale right now.");
        }

        try {
            JSONObject req = new JSONObject();
            req.put("amount", quote.amountPaise());
            req.put("currency", pricingService.currency());
            req.put("receipt", "cart_" + System.currentTimeMillis());
            JSONObject notes = new JSONObject();
            notes.put("userId", userId);
            notes.put("purpose", ORDER_PURPOSE);
            // Everything verification needs to re-derive the amount travels here: the
            // quantities, the rate each line was rung up at, and the offer that applied.
            // Re-reading the live catalogue at verification time instead would break every
            // order in flight the moment a price or an offer changed — a buyer who
            // correctly paid ₹537 would come back to a different expectation and fail
            // verification on money they had already handed over.
            notes.put("projectQty", quote.projectQty());
            notes.put("creditQty", quote.creditQty());
            notes.put("comboQty", quote.comboQty());
            notes.put("bundleQty", quote.bundleQty());
            notes.put("projectPrice", quote.projectPricePaise());
            notes.put("creditPrice", quote.creditPricePaise());
            notes.put("comboPrice", quote.comboPricePaise());
            notes.put("bundlePrice", quote.bundlePricePaise());
            notes.put("comboProjects", quote.comboProjects());
            notes.put("comboCredits", quote.comboCredits());
            notes.put("bundleProjects", quote.bundleProjects());
            notes.put("bundleCredits", quote.bundleCredits());
            // The base the percentage was struck on, so verification re-derives the SAME
            // amount even if the packages rule is flipped while the buyer is in Checkout.
            // Its absence is meaningful too — see the read side.
            notes.put("discountBase", quote.discountBasePaise());
            notes.put("discountPercent", quote.discountPercent());
            notes.put("discountCode", quote.discountCode() == null ? "" : quote.discountCode());
            notes.put("validDays", quote.validDays());
            req.put("notes", notes);

            Order order = razorpayClient.orders.create(req);
            String orderId = order.get("id");
            log.info("Cart order created: user={} order={} projects={} credits={} combos={} "
                     + "bundles={} subtotal={} discount={}% amount={}",
                    userId, orderId, quote.projectQty(), quote.creditQty(), quote.comboQty(),
                    quote.bundleQty(), quote.subtotalPaise(), quote.discountPercent(),
                    quote.amountPaise());
            // Opened while the buyer's request is still on the thread — the only moment we
            // can see their IP, browser and originating page.
            paymentAttemptService.open(orderId, PaymentFlow.CART, userId, quote.amountPaise(),
                    pricingService.currency(), describe(quote), null);

            return CartOrderResponse.builder()
                    .orderId(orderId)
                    .subtotalPaise(quote.subtotalPaise())
                    .discountCode(quote.discountCode())
                    .discountPercent(quote.discountPercent())
                    .discountPaise(quote.discountPaise())
                    .amountPaise(quote.amountPaise())
                    .projectsGranted(quote.projectsGranted())
                    .creditsGranted(quote.creditsGranted())
                    .validDays(quote.validDays())
                    .currency(pricingService.currency())
                    .razorpayKeyId(keyId)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay cart order creation failed: {}", e.getMessage());
            throw new IllegalStateException("Could not start the payment. Please try again.");
        }
    }

    /**
     * Verify the Checkout signature and hand over what the ORDER was for.
     *
     * <p>Signature first, then the order itself: a valid signature proves the payment
     * belongs to some order on this merchant account, not that it belongs to this one, this
     * user, or this basket. All of it is read back from Razorpay rather than trusted.
     *
     * @return the refreshed counter, so the screen can show the new balances at once
     */
    @Transactional
    public CartCatalogueResponse verifyAndCredit(String userId, VerifyCartPurchaseRequest req) {
        verifySignature(req);

        CartPurchase claim;
        try {
            Order order = razorpayClient.orders.fetch(req.getOrderId());
            int amountPaise = ((Number) order.get("amount")).intValue();
            JSONObject notes = order.get("notes");
            String purpose = notes == null ? "" : notes.optString("purpose", "");
            String orderUserId = notes == null ? "" : notes.optString("userId", "");

            int projectQty = notes == null ? 0 : notes.optInt("projectQty", 0);
            int creditQty = notes == null ? 0 : notes.optInt("creditQty", 0);
            int comboQty = notes == null ? 0 : notes.optInt("comboQty", 0);
            // Zero for an order opened before the offer existed. Its notes carry no bundle
            // line, and reading one in would inflate what that payment is redeemed for.
            int bundleQty = notes == null ? 0 : notes.optInt("bundleQty", 0);
            int projectPrice = notes == null ? 0 : notes.optInt("projectPrice", 0);
            int creditPrice = notes == null ? 0 : notes.optInt("creditPrice", 0);
            int comboPrice = notes == null ? 0 : notes.optInt("comboPrice", 0);
            int bundlePrice = notes == null ? 0 : notes.optInt("bundlePrice", 0);
            int comboProjects = notes == null ? 0 : notes.optInt("comboProjects", 0);
            int comboCredits = notes == null ? 0 : notes.optInt("comboCredits", 0);
            int bundleProjects = notes == null ? 0 : notes.optInt("bundleProjects", 0);
            int bundleCredits = notes == null ? 0 : notes.optInt("bundleCredits", 0);
            int discountPercent = notes == null ? 0 : notes.optInt("discountPercent", 0);
            String discountCode = notes == null ? "" : notes.optString("discountCode", "");
            int validDays = notes == null ? 0 : notes.optInt("validDays", 0);

            int subtotal = projectQty * projectPrice + creditQty * creditPrice
                    + comboQty * comboPrice + bundleQty * bundlePrice;
            // Missing on every order opened before the offers stopped applying to packages,
            // and the whole subtotal is exactly what those were discounted on — so the
            // default is not a guess, it is the rule they were priced under. Reading a
            // narrower base into them would refuse a payment the buyer has already made.
            int discountBase = notes == null ? subtotal : notes.optInt("discountBase", subtotal);
            int discount = PricingService.discountPaise(discountBase, discountPercent);
            int projects = projectQty + comboQty * comboProjects + bundleQty * bundleProjects;
            int credits = creditQty + comboQty * comboCredits + bundleQty * bundleCredits;

            // Every one of these is a way the order could be something other than what it
            // claims: the wrong purpose, another account's basket, an empty grant, or an
            // amount that is not what those lines at those rates come to.
            if (!ORDER_PURPOSE.equals(purpose) || !userId.equals(orderUserId)
                    || validDays <= 0
                    || projects + credits <= 0
                    || subtotal <= 0
                    || discountBase < 0 || discountBase > subtotal
                    || amountPaise != subtotal - discount) {
                log.warn("Cart order mismatch: user={} order={} amount={} purpose={} orderUser={} "
                         + "projects={} credits={} subtotal={} discount={}%",
                        userId, req.getOrderId(), amountPaise, purpose, orderUserId,
                        projects, credits, subtotal, discountPercent);
                throw new SecurityException("Payment verification failed.");
            }

            claim = CartPurchase.builder()
                    .paymentId(req.getPaymentId())
                    .orderId(req.getOrderId())
                    .userId(userId)
                    .projectQty(projectQty)
                    .projectPricePaise(projectPrice)
                    .creditQty(creditQty)
                    .creditPricePaise(creditPrice)
                    .comboQty(comboQty)
                    .comboPricePaise(comboPrice)
                    .bundleQty(bundleQty)
                    .bundlePricePaise(bundlePrice)
                    .subtotalPaise(subtotal)
                    .discountCode(discountCode.isBlank() ? null : discountCode)
                    .discountPercent(discountPercent)
                    .discountPaise(discount)
                    .amountPaise(amountPaise)
                    .projectsGranted(projects)
                    .creditsGranted(credits)
                    .validDays(validDays)
                    .build();
        } catch (RazorpayException e) {
            log.error("Razorpay order fetch failed during cart verification: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }

        // Claim the payment exactly once. The pre-check keeps the common case readable; the
        // unique constraint is the race-safe backstop for two concurrent submits.
        if (purchaseRepository.existsByPaymentId(req.getPaymentId())) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }
        try {
            purchaseRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException duplicate) {
            throw new IllegalStateException("This payment has already been redeemed.");
        }

        if (claim.getProjectsGranted() > 0) {
            projectCreditService.creditCatalogueProjects(
                    userId, claim.getProjectsGranted(), claim.getValidDays());
        }
        if (claim.getCreditsGranted() > 0) {
            aiCreditService.creditPurchased(userId, claim.getCreditsGranted(),
                    req.getPaymentId(), claim.getValidDays());
        }
        log.info("Cart redeemed: user={} payment={} projects={} credits={} amount={} validDays={}",
                userId, req.getPaymentId(), claim.getProjectsGranted(), claim.getCreditsGranted(),
                claim.getAmountPaise(), claim.getValidDays());

        return catalogue(userId);
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** What the payment audit and the buyer's bank statement should call this basket. */
    private static String describe(Quote quote) {
        List<String> parts = new ArrayList<>(4);
        if (quote.bundleQty() > 0) {
            parts.add(quote.bundleQty()
                    + (quote.bundleQty() == 1 ? " offer bundle" : " offer bundles"));
        }
        if (quote.comboQty() > 0) {
            parts.add(quote.comboQty() + (quote.comboQty() == 1 ? " combo" : " combos"));
        }
        if (quote.projectQty() > 0) {
            parts.add(quote.projectQty() + (quote.projectQty() == 1 ? " project" : " projects"));
        }
        if (quote.creditQty() > 0) {
            parts.add(quote.creditQty() + (quote.creditQty() == 1 ? " AI credit" : " AI credits"));
        }
        return String.join(" + ", parts);
    }

    /** The Checkout signature must belong to this merchant account. */
    private void verifySignature(VerifyCartPurchaseRequest req) {
        try {
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", req.getOrderId());
            options.put("razorpay_payment_id", req.getPaymentId());
            options.put("razorpay_signature", req.getSignature());
            if (!Utils.verifyPaymentSignature(options, keySecret)) {
                throw new SecurityException("Payment verification failed.");
            }
        } catch (RazorpayException e) {
            log.error("Razorpay cart signature verification error: {}", e.getMessage());
            throw new SecurityException("Payment verification error.");
        }
    }

    /**
     * Refuse an account this counter is not for, BEFORE any money moves.
     *
     * <p>Checked at order time rather than at the credit step for the reason
     * {@link AiCreditPurchaseService} learned the hard way: a role check placed after
     * verification takes the payment first and then rolls back everything it bought,
     * leaving the buyer charged, holding nothing, with no refund path.
     */
    private void requireEligible(String userId) {
        if (!pricingService.buysFromCatalogue(userId)) {
            throw new SecurityException(
                    "This counter is for customer accounts. A shop buys projects at its own "
                    + "plan's rate from the subscription page instead.");
        }
    }
}
