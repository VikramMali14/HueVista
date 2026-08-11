package com.gridstore.huevista.maskreport.controller;

import com.gridstore.huevista.maskreport.dto.MaskReportResponse;
import com.gridstore.huevista.maskreport.dto.UpdateMaskReportRequest;
import com.gridstore.huevista.maskreport.service.MaskReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The admin's side of the mask-report queue.
 *
 * Sits under {@code /api/admin/**}, which SecurityConfig already restricts to
 * ROLE_ADMIN; the {@code @PreAuthorize} is belt-and-braces so the rule survives a
 * change to that matcher.
 */
@RestController
@RequestMapping("/api/admin/mask-reports")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin · mask reports", description = "Reports of AI runs that came out wrong")
public class AdminMaskReportController {

    private final MaskReportService maskReportService;

    @Operation(summary = "The report queue, newest first",
            description = "Open reports only unless `includeResolved` is set.")
    @GetMapping
    public ResponseEntity<List<MaskReportResponse>> queue(
            @Parameter(description = "Include reports already marked resolved")
            @RequestParam(defaultValue = "false") boolean includeResolved) {
        return ResponseEntity.ok(maskReportService.queue(includeResolved));
    }

    @Operation(summary = "How many reports are waiting", description = "For the console's queue badge.")
    @GetMapping("/count")
    public ResponseEntity<Map<String, Long>> openCount() {
        return ResponseEntity.ok(Map.of("open", maskReportService.openCount()));
    }

    @Operation(summary = "Move a report along, optionally with an internal note")
    @PatchMapping("/{id}")
    public ResponseEntity<MaskReportResponse> update(
            @PathVariable String id,
            @Valid @RequestBody UpdateMaskReportRequest request,
            Authentication auth) {
        return ResponseEntity.ok(maskReportService.updateStatus(
                auth.getName(), id, request.getStatus(), request.getAdminNote()));
    }
}
