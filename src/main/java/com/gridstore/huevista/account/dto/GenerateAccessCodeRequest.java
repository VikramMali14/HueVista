package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class GenerateAccessCodeRequest {

    // The walk-in customer's name, shown back to them after redeeming (it becomes
    // the display name of their auto-provisioned account).
    @NotBlank(message = "Customer name is required")
    @Size(max = 120, message = "Customer name is too long")
    private String customerName;

    // How many projects this customer may create. Charged against the retailer's
    // monthly image quota at generation time. Validity is fixed at 10 days (server-side).
    @Min(value = 1, message = "Assign at least 1 project")
    @Max(value = 20, message = "At most 20 projects can be assigned to one code")
    private int projectQuota = 1;

    // Whole paint companies (brand display names) to unlock. Optional. Bounded so a
    // crafted request can't overflow the comma-joined column or smuggle control chars.
    @Size(max = 20, message = "At most 20 companies can be unlocked")
    private List<@Pattern(regexp = "[\\p{L}\\p{N} .&'-]{1,60}",
            message = "Company names may only contain letters, numbers, spaces and . & ' -") String> allowedBrands;

    // Individual shop products (ShopProduct ids) to unlock, in addition to whole
    // companies. Optional. Ids are UUIDs; the service validates they belong to the org.
    @Size(max = 100, message = "At most 100 individual products can be unlocked")
    private List<@Pattern(regexp = "[A-Za-z0-9-]{1,64}",
            message = "Invalid product id") String> allowedProductIds;
}
