package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.BrandResponse;
import com.gridstore.huevista.paint.dto.CreateBrandRequest;
import com.gridstore.huevista.paint.dto.CreateLineRequest;
import com.gridstore.huevista.paint.dto.LineResponse;
import com.gridstore.huevista.paint.model.ProductCategory;
import com.gridstore.huevista.paint.service.PaintCatalogueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Shared brand + product-line reference catalogue (drives the cascading
 * checkboxes). Any authenticated retailer can read it and add a missing
 * brand/line.
 */
@RestController
@RequestMapping("/api/paint")
@RequiredArgsConstructor
@Tag(name = "Paint catalogue", description = "Brands and interior/exterior product lines")
public class PaintCatalogueController {

    private final PaintCatalogueService catalogueService;

    @Operation(summary = "List paint brands")
    @GetMapping("/brands")
    public ResponseEntity<List<BrandResponse>> brands() {
        return ResponseEntity.ok(catalogueService.listBrands());
    }

    @Operation(summary = "Add a brand (deduped by name)")
    @PostMapping("/brands")
    public ResponseEntity<BrandResponse> addBrand(@Valid @RequestBody CreateBrandRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogueService.addBrand(request.getName()));
    }

    @Operation(summary = "List a brand's lines for interior or exterior")
    @GetMapping("/brands/{brandId}/lines")
    public ResponseEntity<List<LineResponse>> lines(
            @PathVariable Long brandId, @RequestParam ProductCategory category) {
        return ResponseEntity.ok(catalogueService.listLines(brandId, category));
    }

    @Operation(summary = "Add a product line to a brand (deduped)")
    @PostMapping("/brands/{brandId}/lines")
    public ResponseEntity<LineResponse> addLine(
            @PathVariable Long brandId, @Valid @RequestBody CreateLineRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalogueService.addLine(brandId, request));
    }
}
