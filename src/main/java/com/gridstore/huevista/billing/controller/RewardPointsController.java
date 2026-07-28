package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.ProjectPurchaseOptionsResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.dto.RewardPointsSummaryResponse;
import com.gridstore.huevista.billing.dto.SubscriptionResponse;
import com.gridstore.huevista.billing.model.RewardPointsLot;
import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.gridstore.huevista.billing.service.RewardPointsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Reward points: balance, the point price list, what expires when, and the four things
 * points buy.
 *
 * Separate from {@code /api/billing/wallet} on purpose — that is rupees at rupee prices
 * with no expiry, this is points at point prices with a one-year life. The
 * RETAILER-only rule is enforced in {@link RewardPointsService}, not here, so it holds
 * for every caller rather than for this controller alone.
 */
@RestController
@RequestMapping("/api/billing/points")
@RequiredArgsConstructor
@Tag(name = "Reward Points", description = "Kiosk reward points: balance, expiry and what they buy")
public class RewardPointsController {

    private final RewardPointsService pointsService;
    private final PricingService pricingService;
    private final BillingService billingService;
    private final ProjectCreditService projectCreditService;

    @Operation(summary = "My reward points",
            description = "Spendable balance, the point price of each purchase, and every live "
                    + "batch with its expiry date. Points last one year from the day they are "
                    + "earned; spending always uses the soonest-expiring batch first.")
    @GetMapping
    public ResponseEntity<RewardPointsSummaryResponse> summary(
            @AuthenticationPrincipal UserDetails userDetails) {
        String userId = userDetails.getUsername();
        List<RewardPointsLot> lots = pointsService.liveLots(userId);
        RewardPointsLot next = lots.stream().findFirst().orElse(null);

        return ResponseEntity.ok(RewardPointsSummaryResponse.builder()
                .balance(pointsService.balance(userId))
                .pointsPerSale(pricingService.kioskBonusPoints())
                .validityDays(pricingService.pointsValidityDays())
                .expiryWarningDays(pricingService.pointsExpiryWarningDays())
                .imagePrice(pricingService.pointsPriceImage())
                .autoMaskPrice(pricingService.pointsPriceAutoMask())
                .projectPrice(pricingService.pointsPriceProject())
                .reopenPrice(pricingService.pointsPriceReopen())
                .nextExpiringPoints(next != null ? next.getPointsRemaining() : null)
                .nextExpiryAt(next != null ? next.getExpiresAt() : null)
                .lots(lots.stream().map(RewardPointsSummaryResponse.LotRow::from).toList())
                .recentActivity(pointsService.recentActivity(userId).stream()
                        .map(RewardPointsSummaryResponse.ActivityRow::from).toList())
                .build());
    }

    @Operation(summary = "Spend points on one extra image",
            description = "Debits the point price of an extra image and credits one to the active "
                    + "subscription. 402 when the balance is short.")
    @PostMapping("/pay/image-credit")
    public ResponseEntity<SubscriptionResponse> payImageCredit(
            @AuthenticationPrincipal UserDetails userDetails) {
        String userId = userDetails.getUsername();
        pointsService.spend(userId, pricingService.pointsPriceImage(),
                RewardPointsTransaction.Type.SPENT_ON_IMAGE, null);
        return ResponseEntity.ok(billingService.creditPurchasedImage(userId));
    }

    @Operation(summary = "Spend points on one extra AI auto-mask",
            description = "Debits the point price of an auto-mask run and credits one to the "
                    + "active subscription. 402 when the balance is short.")
    @PostMapping("/pay/auto-mask-credit")
    public ResponseEntity<SubscriptionResponse> payAutoMaskCredit(
            @AuthenticationPrincipal UserDetails userDetails) {
        String userId = userDetails.getUsername();
        pointsService.spend(userId, pricingService.pointsPriceAutoMask(),
                RewardPointsTransaction.Type.SPENT_ON_AUTO_MASK, null);
        return ResponseEntity.ok(billingService.creditPurchasedAutoMask(userId));
    }

    @Operation(summary = "Spend points on one project",
            description = "Debits the point price of a project and issues one project credit. "
                    + "Needs no active plan — this is what points are worth to a shop between "
                    + "subscriptions. 402 when the balance is short.")
    @PostMapping("/pay/project-credit")
    public ResponseEntity<ProjectPurchaseOptionsResponse> payProjectCredit(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(projectCreditService.payWithPoints(userDetails.getUsername()));
    }

    @Operation(summary = "Spend points to reopen a project",
            description = "Debits the point price of a reopen and gives the project another "
                    + "validity window. 402 when the balance is short.")
    @PostMapping("/pay/project-reopen/{projectId}")
    public ResponseEntity<ProjectReopenResponse> payProjectReopen(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String projectId) {
        return ResponseEntity.ok(
                projectCreditService.reopenWithPoints(userDetails.getUsername(), projectId));
    }
}
