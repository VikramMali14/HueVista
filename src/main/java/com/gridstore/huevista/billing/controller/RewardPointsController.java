package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.BuyPointsRequest;
import com.gridstore.huevista.billing.dto.PointsOrderResponse;
import com.gridstore.huevista.billing.dto.ProjectPurchaseOptionsResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.dto.RewardPointsSummaryResponse;
import com.gridstore.huevista.billing.dto.VerifyPointsPurchaseRequest;
import com.gridstore.huevista.billing.model.RewardPointsLot;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import com.gridstore.huevista.billing.service.PointsPurchaseService;
import com.gridstore.huevista.billing.service.RewardPointsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Points: balance, the price list, what expires when, buying more, and the two things
 * points buy — an extra project, and another window on one that has lapsed.
 *
 * A shop buys points here and spends points here. The same extra project can also be
 * bought with money on {@code /api/billing/projects} at a slightly dearer rate; that rail
 * lives with the other Razorpay flows rather than here, because nothing about it involves
 * points. The RETAILER-only rule is enforced in {@link RewardPointsService}, not in this
 * class, so it holds for every caller rather than for this controller alone.
 */
@RestController
@RequestMapping("/api/billing/points")
@RequiredArgsConstructor
@Tag(name = "Reward Points", description = "Kiosk reward points: balance, expiry and what they buy")
public class RewardPointsController {

    private final RewardPointsService pointsService;
    private final PricingService pricingService;
    private final ProjectCreditService projectCreditService;
    private final PointsPurchaseService pointsPurchaseService;

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
                .rupeesPerPoint(pricingService.rupeesPerPoint())
                .minPurchase(pricingService.pointsMinPurchase())
                .maxPurchase(pricingService.pointsMaxPurchase())
                .validityDays(pricingService.pointsValidityDays())
                .expiryWarningDays(pricingService.pointsExpiryWarningDays())
                .projectPrice(pricingService.pointsPriceProject(userId))
                .reopenPrice(pricingService.pointsPriceReopen())
                .nextExpiringPoints(next != null ? next.getPointsRemaining() : null)
                .nextExpiryAt(next != null ? next.getExpiresAt() : null)
                .lots(lots.stream().map(RewardPointsSummaryResponse.LotRow::from).toList())
                .recentActivity(pointsService.recentActivity(userId).stream()
                        .map(RewardPointsSummaryResponse.ActivityRow::from).toList())
                .build());
    }

    @Operation(summary = "What a project costs in points",
            description = "The point price of a project and a reopen, the caller's balance to "
                    + "weigh them against, how long a bought project stays open, and any "
                    + "already-paid-for projects waiting to be created.")
    @GetMapping("/project-options")
    public ResponseEntity<ProjectPurchaseOptionsResponse> projectOptions(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(projectCreditService.getOptions(userDetails.getUsername()));
    }

    @Operation(summary = "Buy points (order)",
            description = "Creates a Razorpay order for the requested number of points. The "
                    + "amount is derived server-side from the count at the configured rate, so "
                    + "the client never names a price.")
    @PostMapping("/order")
    public ResponseEntity<PointsOrderResponse> createOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BuyPointsRequest request) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(pointsPurchaseService.createOrder(userDetails.getUsername(), request.getPoints()));
    }

    @Operation(summary = "Buy points (verify)",
            description = "Verifies the Razorpay Checkout signature and credits the points the "
                    + "order was for. Replay-protected — one payment credits exactly once. "
                    + "Bought points expire on the same one-year clock as earned ones.")
    @PostMapping("/verify")
    public ResponseEntity<RewardPointsSummaryResponse> verifyPurchase(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyPointsPurchaseRequest request) {
        pointsPurchaseService.verifyAndCredit(userDetails.getUsername(), request);
        return summary(userDetails);
    }

    @Operation(summary = "Spend points on one extra project",
            description = "Debits the point price of a project AT THE CALLER'S PLAN RATE (80 with "
                    + "no plan, down to 40 on Business) and grants one project: added to the live "
                    + "plan's allowance if there is one, otherwise issued as a standalone credit. "
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
