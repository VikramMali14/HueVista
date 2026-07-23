package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.paint.dto.ShopProductResponse;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * What a redeemed customer was assigned by their retailer: the whole companies
 * (brands) unlocked and the individual shop products picked for them. Backs the
 * customer's "assigned products" page.
 */
@Data
@Builder
public class AssignedProductsResponse {
    private String shopName;
    // Whole companies unlocked. Empty means no company restriction (all brands).
    private List<String> allowedBrands;
    // Individually unlocked products, resolved to full listings.
    private List<ShopProductResponse> products;
}
