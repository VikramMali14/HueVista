package com.gridstore.huevista.billing.controller;

import com.gridstore.huevista.account.dto.CustomerEntitlementResponse;
import com.gridstore.huevista.billing.dto.ProjectCreditOrderResponse;
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
@Tag(name = "Project Credits", description = "One-time payment to unlock one extra customer project")
public class ProjectCreditController {

    private final ProjectCreditService projectCreditService;

    @Operation(summary = "Create a one-time payment order for one extra project")
    @PostMapping("/order")
    public ResponseEntity<ProjectCreditOrderResponse> createOrder(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(projectCreditService.createOrder(userDetails.getUsername()));
    }

    @Operation(summary = "Verify the Checkout payment and credit one project")
    @PostMapping("/verify")
    public ResponseEntity<CustomerEntitlementResponse> verify(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody VerifyProjectCreditRequest request) {
        return ResponseEntity.ok(projectCreditService.verifyAndCredit(userDetails.getUsername(), request));
    }
}
