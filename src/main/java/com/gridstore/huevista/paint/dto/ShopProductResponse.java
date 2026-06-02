package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.ProductCategory;
import com.gridstore.huevista.paint.model.QualityTier;
import com.gridstore.huevista.paint.model.ShopProduct;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class ShopProductResponse {
    private String id;
    private Long lineId;
    private String brandName;
    private String lineName;
    private ProductCategory category;
    private BigDecimal price;
    private String priceUnit;
    private String packSize;
    private String coverage;
    private String finish;
    private QualityTier qualityTier;
    private Integer brightness;
    private String imageUrl;
    private String features;
    private String description;
    private LocalDateTime createdAt;

    public static ShopProductResponse from(ShopProduct p) {
        var line = p.getLine();
        return ShopProductResponse.builder()
                .id(p.getId())
                .lineId(line != null ? line.getId() : null)
                .brandName(line != null && line.getBrand() != null ? line.getBrand().getName() : null)
                .lineName(line != null ? line.getName() : null)
                .category(line != null ? line.getCategory() : null)
                .price(p.getPrice())
                .priceUnit(p.getPriceUnit())
                .packSize(p.getPackSize())
                .coverage(p.getCoverage())
                .finish(p.getFinish())
                .qualityTier(p.getQualityTier())
                .brightness(p.getBrightness())
                .imageUrl(p.getImageUrl())
                .features(p.getFeatures())
                .description(p.getDescription())
                .createdAt(p.getCreatedAt())
                .build();
    }
}
