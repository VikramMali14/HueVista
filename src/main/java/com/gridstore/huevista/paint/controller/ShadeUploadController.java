package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.BrandResponse;
import com.gridstore.huevista.paint.dto.ShadeUploadRequest;
import com.gridstore.huevista.paint.dto.ShadeUploadResponse;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.service.ShadeUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public bulk-upload of a company's shades from a JSON array (the /shade-upload page).
 * Kept deliberately simple: list companies for the dropdown, and accept an array of
 * shades for a chosen (or newly named) company. No AI enrichment runs here.
 */
@RestController
@RequestMapping("/api/shade-upload")
@RequiredArgsConstructor
@Tag(name = "Shade upload", description = "Public bulk import of a company's shades")
public class ShadeUploadController {

    private final ShadeUploadService uploadService;
    private final BrandRepository brandRepository;

    @Operation(summary = "List companies for the upload dropdown")
    @SecurityRequirements
    @GetMapping("/brands")
    public List<BrandResponse> brands() {
        return brandRepository.findAllByOrderByNameAsc().stream().map(BrandResponse::from).toList();
    }

    @Operation(summary = "Bulk upload shades for a company (existing or new)")
    @SecurityRequirements
    @PostMapping
    public ResponseEntity<ShadeUploadResponse> upload(@RequestBody ShadeUploadRequest request) {
        return ResponseEntity.ok(
                uploadService.upload(request.getBrandSlug(), request.getBrandName(), request.getShades()));
    }
}
