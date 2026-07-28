package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.CreateSubscriptionRequest;
import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifySubscriptionRequest;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PdfQuotaService;
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
    private final com.gridstore.huevista.billing.service.PricingService pricingService;

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
        return ResponseEntity.ok(
                billingService.verifyAndActivateSubscription(userDetails.getUsername(), request));
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
            description = "Returns all plan options with base pricing, GST, image / auto-mask / PDF "
                    + "limits and the point price of overage.")
    @GetMapping("/plans")
    public ResponseEntity<List<Map<String, Object>>> getPlans() {
        // FREE is granted with a new shop, never sold — listing it here would put a
        // "₹0/mo" card on the pricing page whose button can only ever answer "there's
        // nothing to pay". ENTERPRISE stays listed because it IS a plan you can have,
        // just not one you buy through Checkout.
        var plans = java.util.Arrays.stream(com.gridstore.huevista.billing.model.Plan.values())
            .filter(p -> !p.isFree())
            .map(p -> {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("plan", p.name());
                m.put("displayName", p.getDisplayName());
                m.put("priceInPaise", p.getPriceInPaise());
                m.put("priceInRupees", p.priceInRupees());
                m.put("taxPercent", com.gridstore.huevista.billing.model.Plan.GST_PERCENT);
                m.put("priceWithTaxInPaise", p.priceWithTaxInPaise());
                m.put("priceWithTaxInRupees", p.priceWithTaxInRupees());
                // Kept under the historical "monthlyAiLimit" key for API compatibility;
                // it counts IMAGES processed (clean-up is compulsory on every image).
                m.put("monthlyAiLimit", p.getMonthlyImageLimit() == Integer.MAX_VALUE
                        ? "unlimited" : p.getMonthlyImageLimit());
                m.put("monthlyImageLimit", p.getMonthlyImageLimit() == Integer.MAX_VALUE
                        ? "unlimited" : p.getMonthlyImageLimit());
                m.put("monthlyAutoMaskLimit", p.getMonthlyAutoMaskLimit() == Integer.MAX_VALUE
                        ? "unlimited" : p.getMonthlyAutoMaskLimit());
                m.put("pdfImageLimit", p.getPdfImageLimit());
                m.put("monthlyPdfLimit", p.getMonthlyPdfLimit() == Integer.MAX_VALUE
                        ? "unlimited" : p.getMonthlyPdfLimit());
                // Overage is priced in POINTS, not rupees — there is no cash per-item
                // checkout to quote a rupee figure for.
                m.put("imageOveragePricePoints", pricingService.pointsPriceImage());
                m.put("autoMaskOveragePricePoints", pricingService.pointsPriceAutoMask());
                return m;
            }).toList();
        return ResponseEntity.ok(plans);
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
