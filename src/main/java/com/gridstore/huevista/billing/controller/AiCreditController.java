package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.AiCreditOrderResponse;
import com.gridstore.huevista.billing.dto.AiCreditSummaryResponse;
import com.gridstore.huevista.billing.dto.BuyAiCreditsRequest;
import com.gridstore.huevista.billing.dto.VerifyAiCreditPurchaseRequest;
import com.gridstore.huevista.billing.service.AiCreditPurchaseService;
import com.gridstore.huevista.billing.service.AiCreditService;
import com.gridstore.huevista.billing.service.PaymentAttemptService;
import com.gridstore.huevista.billing.service.PricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * The AI image wallet: balance, today's price, and topping it up.
 *
 * One wallet, two kinds of holder. A SHOP holds credits so it can make images for the rooms
 * it works itself; a CUSTOMER holds them because a project a shop gave them includes no
 * image of its own, and this is the only way to get one. The endpoints are identical for
 * both — the role check lives in {@link AiCreditService}, so it holds for every caller
 * rather than for this controller alone.
 *
 * <p>There is no spend endpoint here on purpose. A credit only ever buys one thing, and it
 * is spent by asking for that thing ({@code POST /api/projects/{id}/renders}) rather than
 * in a separate step a client could take and then fail to follow through on — which would
 * leave a customer charged for a picture nobody ever asked the model for.
 */
@RestController
@RequestMapping("/api/billing/ai-credits")
@RequiredArgsConstructor
@Tag(name = "AI Credits", description = "The AI image wallet: balance, price and top-ups")
public class AiCreditController {

    private final AiCreditService aiCreditService;
    private final AiCreditPurchaseService purchaseService;
    private final PricingService pricingService;
    private final PaymentAttemptService paymentAttemptService;

    @Operation(summary = "My AI image wallet",
            description = "Spendable credits, what one costs today (with the launch discount "
                    + "applied and the list price beside it), the bounds on a top-up, and the "
                    + "last twenty movements. Credits never expire and one credit is one AI "
                    + "image. Returns eligible=false for an account that cannot hold them.")
    @GetMapping
    public ResponseEntity<AiCreditSummaryResponse> summary(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(summaryFor(userDetails.getUsername()));
    }

    @Operation(summary = "Buy AI image credits (order)",
            description = "Creates a Razorpay order for the requested number of credits. The "
                    + "amount is derived server-side from the count at the current price and "
                    + "discount, so the client never names a price.")
    @PostMapping("/order")
    public ResponseEntity<AiCreditOrderResponse> createOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BuyAiCreditsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(purchaseService.createOrder(userDetails.getUsername(), request.getCredits()));
    }

    @Operation(summary = "Buy AI image credits (verify)",
            description = "Verifies the Razorpay Checkout signature and credits the wallet with "
                    + "the credits the order was for. Replay-protected — one payment credits "
                    + "exactly once. Returns the refreshed wallet.")
    @PostMapping("/verify")
    public ResponseEntity<AiCreditSummaryResponse> verifyPurchase(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyAiCreditPurchaseRequest request) {
        paymentAttemptService.recordVerification(request.getOrderId(), request.getPaymentId(),
                () -> purchaseService.verifyAndCredit(userDetails.getUsername(), request));
        return ResponseEntity.ok(summaryFor(userDetails.getUsername()));
    }

    private AiCreditSummaryResponse summaryFor(String userId) {
        return AiCreditSummaryResponse.builder()
                .balance(aiCreditService.balance(userId))
                .eligible(aiCreditService.isEligible(userId))
                // Priced for THIS account: a customer reads the catalogue's ₹35, a shop the
                // shop rate. The panel quotes whatever comes back here, so the button can
                // never name a price the order would then be opened at differently.
                .pricePaise(pricingService.aiCreditPricePaise(userId, 1))
                .listPricePaise(pricingService.aiCreditListPricePaise(userId))
                .discountPercent(pricingService.aiCreditDiscountPercent(userId))
                .minPurchase(pricingService.aiCreditMinPurchase())
                .maxPurchase(pricingService.aiCreditMaxPurchase())
                .renderCost(pricingService.aiCreditRenderCost())
                .renderTiers(java.util.Arrays.stream(
                                com.gridstore.huevista.project.model.ProjectRender.Quality.values())
                        .map(q -> AiCreditSummaryResponse.RenderTier.of(
                                q.name(), pricingService.aiCreditRenderCost(q)))
                        .toList())
                .soonestExpiryAt(aiCreditService.soonestExpiry(userId).orElse(null))
                .expiringCredits(aiCreditService.creditsExpiringSoonest(userId))
                .currency(pricingService.currency())
                .recentActivity(aiCreditService.recentActivity(userId).stream()
                        .map(AiCreditSummaryResponse.ActivityRow::from).toList())
                .build();
    }
}
