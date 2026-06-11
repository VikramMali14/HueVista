package com.gridstore.huevista.painter.controller;

import com.gridstore.huevista.painter.dto.*;
import com.gridstore.huevista.painter.service.PaintJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@Tag(name = "Paint Jobs", description = "Retailer-routed jobs from a project to a painter")
public class PaintJobController {

    private final PaintJobService jobService;

    @Operation(summary = "Create a paint job (retailer owner only)")
    @PostMapping
    public ResponseEntity<PaintJobResponse> create(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreatePaintJobRequest request) {
        PaintJobResponse resp = jobService.createJob(userDetails.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @Operation(summary = "Get a single job", description = "Visible to: the painter, the customer, or the retailer owner.")
    @GetMapping("/{jobId}")
    public ResponseEntity<PaintJobResponse> get(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId) {
        return ResponseEntity.ok(jobService.getJob(userDetails.getUsername(), jobId));
    }

    @Operation(summary = "List my jobs as painter")
    @GetMapping("/mine/painter")
    public ResponseEntity<List<PaintJobResponse>> listMineAsPainter(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 200") @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(jobService.listForPainter(userDetails.getUsername(), page, size));
    }

    @Operation(summary = "List jobs for a retailer (owner only)")
    @GetMapping("/by-retailer/{retailerOrgId}")
    public ResponseEntity<List<PaintJobResponse>> listByRetailer(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String retailerOrgId,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 200") @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(jobService.listForRetailer(userDetails.getUsername(), retailerOrgId, page, size));
    }

    @Operation(summary = "List jobs as the customer")
    @GetMapping("/mine/customer")
    public ResponseEntity<List<PaintJobResponse>> listMineAsCustomer(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 200") @RequestParam(defaultValue = "200") int size) {
        return ResponseEntity.ok(jobService.listForCustomer(userDetails.getUsername(), page, size));
    }

    @Operation(summary = "Accept a job (painter only)", description = "Sets status ACCEPTED, captures quote + estimated days + optional schedule.")
    @PostMapping("/{jobId}/accept")
    public ResponseEntity<PaintJobResponse> accept(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId,
            @Valid @RequestBody AcceptPaintJobRequest request) {
        return ResponseEntity.ok(jobService.accept(userDetails.getUsername(), jobId, request));
    }

    @Operation(summary = "Decline a job (painter only)")
    @PostMapping("/{jobId}/decline")
    public ResponseEntity<PaintJobResponse> decline(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId,
            @Valid @RequestBody DeclinePaintJobRequest request) {
        return ResponseEntity.ok(jobService.decline(userDetails.getUsername(), jobId, request));
    }

    @Operation(summary = "Mark a job IN_PROGRESS (painter only)")
    @PostMapping("/{jobId}/start")
    public ResponseEntity<PaintJobResponse> start(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId) {
        return ResponseEntity.ok(jobService.markInProgress(userDetails.getUsername(), jobId));
    }

    @Operation(summary = "Mark a job COMPLETED (painter only)")
    @PostMapping("/{jobId}/complete")
    public ResponseEntity<PaintJobResponse> complete(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId) {
        return ResponseEntity.ok(jobService.markCompleted(userDetails.getUsername(), jobId));
    }

    @Operation(summary = "Cancel a job (retailer owner or customer)")
    @PostMapping("/{jobId}/cancel")
    public ResponseEntity<PaintJobResponse> cancel(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String jobId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.getOrDefault("reason", null) : null;
        return ResponseEntity.ok(jobService.cancel(userDetails.getUsername(), jobId, reason));
    }
}
