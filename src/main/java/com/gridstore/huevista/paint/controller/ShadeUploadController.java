package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.BrandResponse;
import com.gridstore.huevista.paint.dto.ShadeUploadRequest;
import com.gridstore.huevista.paint.dto.ShadeUploadResponse;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.service.ShadeUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only bulk import of a company's shades from a JSON array (the admin
 * /admin/shades page). List companies for the dropdown, and accept an array of shades
 * for a chosen (or newly named) company. No AI enrichment runs here. Gated to
 * ROLE_ADMIN both by the {@code /api/admin/**} security rule and {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/admin/paint")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Shade upload", description = "Admin bulk import of a company's shades")
public class ShadeUploadController {

    private final ShadeUploadService uploadService;
    private final BrandRepository brandRepository;

    @Operation(summary = "List companies for the upload dropdown")
    @GetMapping("/brands")
    public List<BrandResponse> brands() {
        return brandRepository.findAllByOrderByNameAsc().stream().map(BrandResponse::from).toList();
    }

    @Operation(summary = "Bulk upload shades for a company (existing or new)")
    @PostMapping("/upload")
    public ResponseEntity<ShadeUploadResponse> upload(@RequestBody ShadeUploadRequest request) {
        // Enrich with Claude by default; only skip if the caller explicitly opts out.
        boolean enrich = request.getEnrich() == null || request.getEnrich();
        return ResponseEntity.ok(uploadService.upload(
                request.getBrandSlug(), request.getBrandName(), request.getShades(), enrich));
    }
}
