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
    private final com.gridstore.huevista.billing.service.ImageCreditService imageCreditService;
    private final com.gridstore.huevista.billing.service.BillingWalletService walletService;

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

    @Operation(summary = "Cancel subscription",
            description = "Marks the active subscription to cancel at the end of the current billing period.")
    @PostMapping("/subscriptions/cancel")
    public ResponseEntity<SubscriptionResponse> cancelSubscription(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(billingService.cancelSubscription(userDetails.getUsername()));
    }

    @Operation(summary = "Get available plans",
            description = "Returns all plan options with base pricing, GST, image / auto-mask / PDF "
                    + "limits and the pay-per-image overage price.")
    @GetMapping("/plans")
    public ResponseEntity<List<Map<String, Object>>> getPlans() {
        var plans = java.util.Arrays.stream(com.gridstore.huevista.billing.model.Plan.values())
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
                m.put("imageOveragePriceInPaise",
                        com.gridstore.huevista.billing.model.Plan.IMAGE_OVERAGE_PRICE_PAISE);
                m.put("imageOveragePriceWithTaxInPaise",
                        com.gridstore.huevista.billing.model.Plan.imageOveragePriceWithTaxInPaise());
                m.put("autoMaskOveragePriceInPaise",
                        com.gridstore.huevista.billing.model.Plan.AUTO_MASK_OVERAGE_PRICE_PAISE);
                m.put("autoMaskOveragePriceWithTaxInPaise",
                        com.gridstore.huevista.billing.model.Plan.autoMaskOveragePriceWithTaxInPaise());
                return m;
            }).toList();
        return ResponseEntity.ok(plans);
    }

    @Operation(summary = "Buy one extra image (order)",
            description = "Creates a one-time Razorpay order for a single extra image at Rs. 50 + 18% GST, "
                    + "used once the monthly image quota is spent. Requires an active subscription.")
    @PostMapping("/image-credits/order")
    public ResponseEntity<com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse> createImageCreditOrder(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(imageCreditService.createOrder(userDetails.getUsername()));
    }

    @Operation(summary = "Buy one extra image (verify)",
            description = "Verifies the Razorpay Checkout signature and credits one extra image to the "
                    + "active subscription. Replay-protected — one payment buys exactly one image.")
    @PostMapping("/image-credits/verify")
    public ResponseEntity<SubscriptionResponse> verifyImageCredit(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest request) {
        return ResponseEntity.ok(imageCreditService.verifyAndCredit(userDetails.getUsername(), request));
    }

    // ── Prepaid billing wallet ───────────────────────────────────────────────

    @Operation(summary = "Get my billing wallet",
            description = "Prepaid wallet balance, pay-per-use prices and the recent statement. "
                    + "The wallet pays for overage (extra images at Rs. 50 + GST, extra AI "
                    + "auto-masks at Rs. 25 + GST) once monthly allowances are spent.")
    @GetMapping("/wallet")
    public ResponseEntity<com.gridstore.huevista.billing.dto.BillingWalletSummaryResponse> getWallet(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(walletService.getWallet(userDetails.getUsername()));
    }

    @Operation(summary = "Top up the wallet (order)",
            description = "Creates a one-time Razorpay order that adds the paid amount to the wallet "
                    + "once verified. Requires an active subscription.")
    @PostMapping("/wallet/topup/order")
    public ResponseEntity<com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse> createWalletTopUpOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody com.gridstore.huevista.billing.dto.WalletTopUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(walletService.createTopUpOrder(userDetails.getUsername(), request.getAmountPaise()));
    }

    @Operation(summary = "Top up the wallet (verify)",
            description = "Verifies the Razorpay Checkout signature and credits the order amount to the "
                    + "wallet. Replay-protected — one payment credits exactly once.")
    @PostMapping("/wallet/topup/verify")
    public ResponseEntity<com.gridstore.huevista.billing.dto.BillingWalletSummaryResponse> verifyWalletTopUp(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest request) {
        return ResponseEntity.ok(walletService.verifyTopUp(userDetails.getUsername(), request));
    }

    @Operation(summary = "Pay for one extra image from the wallet",
            description = "Atomically debits Rs. 59 (Rs. 50 + 18% GST) from the wallet and credits one "
                    + "extra image to the active subscription. 402 when the balance is insufficient.")
    @PostMapping("/wallet/pay/image-credit")
    public ResponseEntity<SubscriptionResponse> walletPayImageCredit(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(walletService.payForImageCredit(userDetails.getUsername()));
    }

    @Operation(summary = "Pay for one extra AI auto-mask from the wallet",
            description = "Atomically debits Rs. 29.50 (Rs. 25 + 18% GST) from the wallet and credits one "
                    + "extra AI auto-mask run to the active subscription. 402 when the balance is "
                    + "insufficient.")
    @PostMapping("/wallet/pay/auto-mask-credit")
    public ResponseEntity<SubscriptionResponse> walletPayAutoMaskCredit(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(walletService.payForAutoMaskCredit(userDetails.getUsername()));
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
