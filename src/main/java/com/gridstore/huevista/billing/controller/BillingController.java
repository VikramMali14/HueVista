package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.CreateSubscriptionRequest;
import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import com.gridstore.huevista.billing.dto.ProjectOrderResponse;
import com.gridstore.huevista.billing.dto.ProjectPurchaseOptionsResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectPurchaseRequest;
import com.gridstore.huevista.billing.dto.VerifySubscriptionRequest;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PaymentAttemptService;
import com.gridstore.huevista.billing.service.PdfQuotaService;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.gridstore.huevista.billing.service.ProjectPurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/billing")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "Subscription management and usage tracking")
public class BillingController {

    private final BillingService billingService;
    private final PdfQuotaService pdfQuotaService;
    private final ProjectPurchaseService projectPurchaseService;
    private final ProjectCreditService projectCreditService;
    private final PaymentAttemptService paymentAttemptService;

    @Operation(summary = "Create subscription",
            description = "Creates a Razorpay subscription and returns a payment URL for checkout.")
    @PostMapping("/subscriptions")
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateSubscriptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(billingService.createSubscription(userDetails.getUsername(), request));
    }

    @Operation(summary = "Verify subscription payment",
            description = "Verifies the Razorpay Checkout signature and activates the subscription immediately.")
    @PostMapping("/subscriptions/verify")
    public ResponseEntity<SubscriptionResponse> verifySubscription(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifySubscriptionRequest request) {
        return ResponseEntity.ok(paymentAttemptService.recordVerification(
                request.getSubscriptionId(), request.getPaymentId(),
                () -> billingService.verifyAndActivateSubscription(userDetails.getUsername(), request)));
    }

    @Operation(summary = "Get current subscription", description = "Returns the current (or most recent) subscription with usage stats.")
    @GetMapping("/subscriptions/current")
    public ResponseEntity<SubscriptionResponse> getCurrentSubscription(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(billingService.getCurrentSubscription(userDetails.getUsername()));
    }

    @Operation(summary = "Get subscription history", description = "Returns all subscriptions for the authenticated user.")
    @GetMapping("/subscriptions")
    public ResponseEntity<List<SubscriptionResponse>> getSubscriptionHistory(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(billingService.getSubscriptionHistory(userDetails.getUsername()));
    }

    @Operation(summary = "Resume a subscription scheduled to end",
            description = "Clears a pending cancellation so the plan keeps renewing. Works for a free "
                    + "trial; for a paid plan already cancelled at Razorpay it explains that the gateway "
                    + "can't un-cancel and the customer should subscribe again (their current period is "
                    + "unaffected either way).")
    @PostMapping("/subscriptions/resume")
    public ResponseEntity<SubscriptionResponse> resumeSubscription(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(billingService.resumeSubscription(userDetails.getUsername()));
    }

    @Operation(summary = "Cancel subscription",
            description = "Marks the active subscription to cancel at the end of the current billing period. "
                    + "Access continues in full until then — including a free trial, which keeps its "
                    + "remaining days instead of ending on the spot.")
    @PostMapping("/subscriptions/cancel")
    public ResponseEntity<SubscriptionResponse> cancelSubscription(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(billingService.cancelSubscription(userDetails.getUsername()));
    }

    @Operation(summary = "Get available plans",
            description = "Returns every plan a shop can be on — the free tier included — with base "
                    + "pricing, GST, monthly project and PDF limits, whether the tier includes colour "
                    + "matching, and what one extra project costs on it in points and in money. "
                    + "`purchasable` says whether it is something checkout can sell.")
    @GetMapping("/plans")
    public ResponseEntity<List<Map<String, Object>>> getPlans() {
        // FREE is listed. It is a real tier now — granted with the account, renewed every
        // month, and the plan a lapsed shop falls back to — so a client comparing tiers has
        // to be able to see it, and `purchasable: false` is what stops a "₹0/mo" card
        // growing a buy button that can only ever answer "there's nothing to pay".
        //
        // ENTERPRISE is still not offered: it was a "contact sales" card with no price, no
        // checkout path and nothing behind it, so every surface that listed it was quoting
        // a product a shop could not have. The enum constant stays for the rows already
        // carrying it and for an admin grant, but nothing sells it.
        var plans = java.util.Arrays.stream(com.gridstore.huevista.billing.model.Plan.values())
            .filter(p -> p != com.gridstore.huevista.billing.model.Plan.ENTERPRISE)
            .map(p -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("plan", p.name());
                m.put("displayName", p.getDisplayName());
                // Whether checkout can sell this tier. The free one is granted, never
                // bought, so a client must render it as a card and not as an offer.
                m.put("purchasable", !p.isFree());
                // The tier ladder, so a client can tell an upgrade from a downgrade
                // without hard-coding a copy of the enum order that silently rots when a
                // tier is added or reordered here.
                m.put("rank", p.ordinal());
                m.put("priceInPaise", p.getPriceInPaise());
                m.put("priceInRupees", p.priceInRupees());
                m.put("taxPercent", com.gridstore.huevista.billing.model.Plan.GST_PERCENT);
                m.put("priceWithTaxInPaise", p.priceWithTaxInPaise());
                m.put("priceWithTaxInRupees", p.priceWithTaxInRupees());
                // Complete projects per cycle. One covers the whole automatic pipeline —
                // the AI photo clean-up AND the AI wall detection — so there is a single
                // number here where there used to be an image quota and an auto-mask one.
                m.put("monthlyProjectLimit", p.getMonthlyProjectLimit() == Integer.MAX_VALUE
                        ? "unlimited" : p.getMonthlyProjectLimit());
                m.put("pdfImageLimit", p.getPdfImageLimit());
                m.put("monthlyPdfLimit", p.getMonthlyPdfLimit() == Integer.MAX_VALUE
                        ? "unlimited" : p.getMonthlyPdfLimit());
                // What one extra project costs ON THIS TIER, once the monthly allowance is
                // spent — cheaper the bigger the plan, on both rails. This is a property of
                // the tier, so it belongs on the card a shop compares tiers with.
                m.put("extraProjectPoints", p.getExtraProjectPoints());
                m.put("extraProjectPriceInPaise", p.getExtraProjectPricePaise());
                m.put("extraProjectPriceWithTaxInPaise", p.extraProjectPriceWithTaxInPaise());
                // The one capability that is a property of the TIER rather than of a
                // quota: colour matching (the Colour finder) is off on the free plan.
                m.put("colorMatching", p.isColorMatching());
                return m;
            }).toList();
        return ResponseEntity.ok(plans);
    }

    @Operation(summary = "Buy extra projects with money (order)",
            description = "Creates a Razorpay order at the CALLER'S plan rate — ₹199 with no plan, "
                    + "₹65 / ₹55 / ₹45 on Starter / Professional / Business. `credits` buys either "
                    + "one project or a bundle of three for two projects' money; no other quantity "
                    + "is priced. The amount is derived server-side, so the client never names a "
                    + "price. Points are the cheaper rail for the same thing (see "
                    + "/api/billing/points/pay/project-credit).")
    @PostMapping("/projects/order")
    public ResponseEntity<ProjectOrderResponse> createProjectOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "1") int credits) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectPurchaseService.createOrder(userDetails.getUsername(), credits));
    }

    @Operation(summary = "Buy one extra project with money (verify)",
            description = "Verifies the Razorpay Checkout signature and grants the project the "
                    + "order was for: added to the live plan's allowance if there is one, otherwise "
                    + "issued as a standalone credit. Replay-protected — one payment buys exactly "
                    + "one project.")
    @PostMapping("/projects/verify")
    public ResponseEntity<ProjectPurchaseOptionsResponse> verifyProjectPurchase(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyProjectPurchaseRequest request) {
        String userId = userDetails.getUsername();
        paymentAttemptService.recordVerification(request.getOrderId(), request.getPaymentId(),
                () -> { projectPurchaseService.verifyAndCredit(userId, request); return null; });
        return ResponseEntity.ok(projectCreditService.getOptions(userId));
    }

    @Operation(summary = "Reopen a lapsed project with money (order)",
            description = "Creates a Razorpay order for another validity window on a project "
                    + "whose own has run out. Flat price, unlike a new project — a reopen buys "
                    + "more time on work already paid for once. Refused up-front (409) when the "
                    + "caller can already work on the project, so nobody pays to unlock something "
                    + "a live plan or a shop code is already unlocking.")
    @PostMapping("/projects/{projectId}/reopen/order")
    public ResponseEntity<ProjectOrderResponse> createReopenOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String projectId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectPurchaseService.createReopenOrder(userDetails.getUsername(), projectId));
    }

    @Operation(summary = "Reopen a lapsed project with money (verify)",
            description = "Verifies the Razorpay Checkout signature and extends the project the "
                    + "ORDER was for — which project is read back from the order, not named by the "
                    + "client. Replay-protected.")
    @PostMapping("/projects/reopen/verify")
    public ResponseEntity<ProjectReopenResponse> verifyReopen(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyProjectPurchaseRequest request) {
        return ResponseEntity.ok(paymentAttemptService.recordVerification(request.getOrderId(), request.getPaymentId(),
                () -> projectPurchaseService.verifyAndCreditReopen(userDetails.getUsername(), request)));
    }

    @Operation(summary = "Buy one more AI render (order)",
            description = "Creates a Razorpay order for another AI image on a project that has "
                    + "spent the one it came with. Flat price. Refused up-front (409) when the "
                    + "project still has a render left, so nobody buys one they already have.")
    @PostMapping("/projects/{projectId}/renders/order")
    public ResponseEntity<ProjectOrderResponse> createRenderOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String projectId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectPurchaseService.createRenderOrder(userDetails.getUsername(), projectId));
    }

    @Operation(summary = "Buy one more AI render (verify)",
            description = "Verifies the Razorpay Checkout signature and adds one render to the "
                    + "project the ORDER named — read back from the order, not from the client. "
                    + "Replay-protected: one payment buys exactly one image.")
    @PostMapping("/projects/renders/verify")
    public ResponseEntity<Void> verifyRenderPurchase(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyProjectPurchaseRequest request) {
        String userId = userDetails.getUsername();
        paymentAttemptService.recordVerification(request.getOrderId(), request.getPaymentId(),
                () -> { projectPurchaseService.verifyAndCreditRender(userId, request); return null; });
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get my colour-board PDF allowance",
            description = "Images-per-PDF and monthly download quota, resolved against whichever plan pays "
                    + "for the caller (a retailer's own; the issuing shop's for customers).")
    @GetMapping("/pdf-allowance")
    public ResponseEntity<PdfAllowanceResponse> getPdfAllowance(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(pdfQuotaService.allowanceForUser(userDetails.getUsername()));
    }

    @Operation(summary = "Charge one colour-board PDF download",
            description = "Atomically reserves one PDF download against the caller's allowance and returns "
                    + "the remaining quota. 402 when the monthly limit is spent.")
    @PostMapping("/pdf-downloads")
    public ResponseEntity<PdfAllowanceResponse> chargePdfDownload(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(pdfQuotaService.reserveForUser(userDetails.getUsername()));
    }
}
