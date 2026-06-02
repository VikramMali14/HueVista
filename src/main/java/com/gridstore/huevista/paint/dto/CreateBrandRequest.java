package com.gridstore.huevista.paint.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateBrandRequest {
    @NotBlank(message = "Brand name is required")
    @Size(max = 80)
    private String name;
}
