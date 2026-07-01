package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.CreateSubscriptionRequest;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.dto.VerifySubscriptionRequest;
import com.gridstore.huevista.billing.service.BillingService;
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

    @Operation(summary = "Get available plans", description = "Returns all plan options with pricing and AI generation limits.")
    @GetMapping("/plans")
    public ResponseEntity<List<Map<String, Object>>> getPlans() {
        var plans = List.of(
            com.gridstore.huevista.billing.model.Plan.values()
        ).stream().map(p -> Map.<String, Object>of(
            "plan", p.name(),
            "displayName", p.getDisplayName(),
            "priceInPaise", p.getPriceInPaise(),
            "priceInRupees", p.priceInRupees(),
            "monthlyAiLimit", p.getMonthlyAiLimit() == Integer.MAX_VALUE ? "unlimited" : p.getMonthlyAiLimit()
        )).toList();
        return ResponseEntity.ok(plans);
    }
}
