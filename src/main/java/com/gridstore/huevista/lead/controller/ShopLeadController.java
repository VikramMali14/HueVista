package com.gridstore.huevista.lead.controller;

import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.lead.dto.ApproveShopRequestRequest;
import com.gridstore.huevista.lead.dto.ShopLeadRequest;
import com.gridstore.huevista.lead.dto.ShopLeadResponse;
import com.gridstore.huevista.lead.dto.ShopRequestStatusResponse;
import com.gridstore.huevista.lead.dto.VerifyShopRequestRequest;
import com.gridstore.huevista.lead.service.ShopLeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Shop-account requests.
 *
 * <p>The three submit/verify/resend endpoints are PUBLIC (rate-limited per IP) — they
 * are the marketing site's "bring HueVista to your counter" form. The queue and the
 * approve/dismiss actions live under {@code /api/admin} and are ROLE_ADMIN via
 * SecurityConfig.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Shop requests", description = "The public shop-account request funnel + the admin queue that works it")
public class ShopLeadController {

    private final ShopLeadService leadService;
    private final AuditService auditService;

    @Operation(summary = "Request a shop account (public)",
            description = "Captures the request and emails a 6-digit code. No account is created here — "
                    + "the request only reaches the admin queue once that code is confirmed.")
    @PostMapping("/api/leads/shop")
    public ResponseEntity<ShopRequestStatusResponse> submit(@Valid @RequestBody ShopLeadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leadService.submit(request));
    }

    @Operation(summary = "Confirm the emailed code (public)",
            description = "Verifies the mailbox and queues the request. From here it is provisioned by an "
                    + "admin in one click, or automatically 24 hours later.")
    @PostMapping("/api/leads/shop/{requestId}/verify")
    public ResponseEntity<ShopRequestStatusResponse> verify(
            @PathVariable String requestId,
            @Valid @RequestBody VerifyShopRequestRequest request) {
        return ResponseEntity.ok(leadService.verifyEmail(requestId, request.getCode()));
    }

    @Operation(summary = "Send another code (public)", description = "Subject to a 60-second cooldown.")
    @PostMapping("/api/leads/shop/{requestId}/resend")
    public ResponseEntity<ShopRequestStatusResponse> resend(@PathVariable String requestId) {
        return ResponseEntity.ok(leadService.resendCode(requestId));
    }

    @Operation(summary = "List shop requests (admin)", description = "Newest first. Paged; defaults to the latest 100.")
    @GetMapping("/api/admin/leads")
    public ResponseEntity<List<ShopLeadResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(leadService.list(page, size));
    }

    @Operation(summary = "Create the account this request asked for (admin)",
            description = "One click: the shop's own details and password become a RETAILER account on the "
                    + "free plan, filed under the chosen distributor (the house one when none is named).")
    @PostMapping("/api/admin/leads/{requestId}/approve")
    public ResponseEntity<ShopLeadResponse> approve(
            @PathVariable String requestId,
            @RequestBody(required = false) ApproveShopRequestRequest body,
            Authentication auth) {
        String distributorOrgId = body != null ? body.getDistributorOrgId() : null;
        ShopLeadResponse approved = leadService.approve(auth.getName(), requestId, distributorOrgId);
        auditService.record(auth.getName(), "SHOP_REQUEST_APPROVED", "USER", approved.getCreatedUserId(),
                "shop account created from request " + requestId);
        return ResponseEntity.ok(approved);
    }

    @Operation(summary = "Turn a request down (admin)",
            description = "Nothing is created and the stored password hash is dropped. The address is free "
                    + "to request again.")
    @PostMapping("/api/admin/leads/{requestId}/dismiss")
    public ResponseEntity<ShopLeadResponse> dismiss(@PathVariable String requestId, Authentication auth) {
        ShopLeadResponse dismissed = leadService.dismiss(auth.getName(), requestId);
        auditService.record(auth.getName(), "SHOP_REQUEST_DISMISSED", "SHOP_REQUEST", requestId, null);
        return ResponseEntity.ok(dismissed);
    }
}
