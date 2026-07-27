package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse;
import com.gridstore.huevista.billing.dto.ProjectPurchaseOptionsResponse;
import com.gridstore.huevista.billing.dto.ProjectReopenResponse;
import com.gridstore.huevista.billing.dto.VerifyProjectCreditRequest;
import com.gridstore.huevista.billing.service.ProjectCreditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/billing/project-credit")
@Tag(name = "Project Credits",
        description = "One-time payments that buy a project, or reopen one whose validity ran out")
public class ProjectCreditController {

    private final ProjectCreditService projectCreditService;

    @Operation(summary = "What buying a project costs this account",
            description = "Today's price, both ends of it (with and without a plan), what a reopen "
                    + "costs, how long a bought project stays open, and how many paid-for projects "
                    + "are already waiting to be created.")
    @GetMapping("/options")
    public ResponseEntity<ProjectPurchaseOptionsResponse> options(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(projectCreditService.getOptions(userDetails.getUsername()));
    }

    @Operation(summary = "Create a one-time payment order for one project",
            description = "Priced by subscription state: cheaper with a live plan, the standalone "
                    + "price without one. A bought project stays fully workable for its validity "
                    + "window, which pauses while a subscription is covering it.")
    @PostMapping("/order")
    public ResponseEntity<ProjectCreditOrderResponse> createOrder(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(projectCreditService.createOrder(userDetails.getUsername()));
    }

    @Operation(summary = "Verify the Checkout payment and credit one project")
    @PostMapping("/verify")
    public ResponseEntity<ProjectPurchaseOptionsResponse> verify(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyProjectCreditRequest request) {
        return ResponseEntity.ok(projectCreditService.verifyAndCredit(userDetails.getUsername(), request));
    }

    @Operation(summary = "Create a payment order to reopen a lapsed project",
            description = "For a project whose validity has run out: pays to add another window so it "
                    + "becomes workable again. Refused while the project is still open.")
    @PostMapping("/reopen/{projectId}/order")
    public ResponseEntity<ProjectCreditOrderResponse> createReopenOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String projectId) {
        return ResponseEntity.ok(
                projectCreditService.createReopenOrder(userDetails.getUsername(), projectId));
    }

    @Operation(summary = "Verify the Checkout payment and reopen the project",
            description = "The project reopened is the one named on the ORDER, not on this request — "
                    + "a signature proves the payment is genuine but says nothing about what it bought.")
    @PostMapping("/reopen/verify")
    public ResponseEntity<ProjectReopenResponse> verifyReopen(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyProjectCreditRequest request) {
        return ResponseEntity.ok(projectCreditService.verifyAndReopen(userDetails.getUsername(), request));
    }
}
