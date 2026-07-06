package com.gridstore.huevista.lead.controller;

import com.gridstore.huevista.lead.dto.ShopLeadRequest;
import com.gridstore.huevista.lead.dto.ShopLeadResponse;
import com.gridstore.huevista.lead.model.ShopLead;
import com.gridstore.huevista.lead.service.ShopLeadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Shop-account requests. The submit endpoint is PUBLIC (rate-limited per IP) —
 * it's the marketing site's "bring HueVista to your counter" form. The list and
 * status endpoints live under /api/admin and are ROLE_ADMIN via SecurityConfig.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Shop leads", description = "Public shop-account requests + the admin queue that works them")
public class ShopLeadController {

    private final ShopLeadService leadService;

    @Operation(summary = "Request a shop account (public)",
            description = "Captures a retailer lead from the marketing site. No account is created — "
                    + "an admin reviews the queue and provisions the shop.")
    @PostMapping("/api/leads/shop")
    public ResponseEntity<ShopLeadResponse> submit(@Valid @RequestBody ShopLeadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(leadService.submit(request));
    }

    @Operation(summary = "List shop leads (admin)", description = "Newest first. Paged; defaults to the latest 100.")
    @GetMapping("/api/admin/leads")
    public ResponseEntity<List<ShopLeadResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(leadService.list(page, size));
    }

    @Operation(summary = "Update a lead's status (admin)",
            description = "Body: {\"status\": \"NEW|CONTACTED|CONVERTED|DISMISSED\"}")
    @PatchMapping("/api/admin/leads/{leadId}/status")
    public ResponseEntity<ShopLeadResponse> updateStatus(
            @PathVariable String leadId,
            @RequestBody Map<String, String> body) {
        ShopLead.Status status;
        try {
            status = ShopLead.Status.valueOf(String.valueOf(body.get("status")).trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("status must be one of NEW, CONTACTED, CONVERTED, DISMISSED");
        }
        return ResponseEntity.ok(leadService.updateStatus(leadId, status));
    }
}
