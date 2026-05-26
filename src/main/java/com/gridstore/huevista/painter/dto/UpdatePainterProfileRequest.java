package com.gridstore.huevista.painter.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class UpdatePainterProfileRequest {

    @Pattern(regexp = "^[+]?[0-9 -]{7,15}$", message = "Phone must be 7–15 digits, optional leading +")
    private String phone;

    @Size(max = 20)
    private List<@Size(min = 1, max = 80) String> serviceAreas;

    @Size(max = 12)
    private List<@Size(min = 1, max = 40) String> specialties;

    @Min(0)
    private Integer yearsExperience;

    private BigDecimal dayRateInr;
}
