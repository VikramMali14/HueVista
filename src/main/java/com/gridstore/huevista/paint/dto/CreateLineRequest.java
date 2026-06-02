package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.ProductCategory;
import com.gridstore.huevista.paint.model.QualityTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateLineRequest {
    @NotBlank(message = "Line name is required")
    @Size(max = 100)
    private String name;

    @NotNull(message = "Category is required")
    private ProductCategory category;

    private QualityTier qualityTier;
    private String defaultFinish;
}
