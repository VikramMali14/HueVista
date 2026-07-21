package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.CreateShopProductRequest;
import com.gridstore.huevista.paint.dto.ShopProductResponse;
import com.gridstore.huevista.paint.service.ShopProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** A retailer org's own paint product listings (price/image/details). */
@RestController
@RequestMapping("/api/organizations/{orgId}/products")
@RequiredArgsConstructor
@Tag(name = "Shop products", description = "A retailer's paint product listings")
public class ShopProductController {

    private final ShopProductService shopProductService;

    @Operation(summary = "List this shop's products")
    @GetMapping
    public ResponseEntity<List<ShopProductResponse>> list(@PathVariable String orgId, Authentication auth) {
        return ResponseEntity.ok(shopProductService.list(auth.getName(), orgId));
    }

    @Operation(summary = "Add a product listing for a chosen line")
    @PostMapping
    public ResponseEntity<ShopProductResponse> create(
            @PathVariable String orgId, @Valid @RequestBody CreateShopProductRequest request, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(shopProductService.create(auth.getName(), orgId, request));
    }

    @Operation(summary = "Update a product listing")
    @PutMapping("/{productId}")
    public ResponseEntity<ShopProductResponse> update(
            @PathVariable String orgId, @PathVariable String productId,
            @Valid @RequestBody CreateShopProductRequest request, Authentication auth) {
        return ResponseEntity.ok(shopProductService.update(auth.getName(), orgId, productId, request));
    }

    @Operation(summary = "Remove a product listing")
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(
            @PathVariable String orgId, @PathVariable String productId, Authentication auth) {
        shopProductService.delete(auth.getName(), orgId, productId);
        return ResponseEntity.noContent().build();
    }
}
