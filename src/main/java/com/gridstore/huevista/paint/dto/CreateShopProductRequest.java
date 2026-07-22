package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.QualityTier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateShopProductRequest {
    @NotNull(message = "lineId is required")
    private Long lineId;

    private BigDecimal price;
    @Size(max = 40)
    private String priceUnit;
    @Size(max = 40)
    private String packSize;
    @Size(max = 200)
    private String coverage;
    @Size(max = 60)
    private String finish;

    private QualityTier qualityTier;

    @Min(1)
    @Max(10)
    private Integer brightness;

    @Size(max = 2048)
    @Pattern(regexp = "^(https?://.+|/.+)?$", message = "imageUrl must be an http(s) URL or a relative path")
    private String imageUrl;
    @Size(max = 4000)
    private String features;
    @Size(max = 4000)
    private String description;
}
