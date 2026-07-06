package com.gridstore.huevista.lead.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShopLeadRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 120)
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 200)
    private String email;

    @Size(max = 32)
    private String phone;

    @NotBlank(message = "Shop name is required")
    @Size(max = 200)
    private String shopName;

    @Size(max = 120)
    private String city;

    @Size(max = 120)
    private String state;

    /** Tier of interest — free text from the form ("starter" | "pro" | "business"). */
    @Size(max = 40)
    private String tier;

    @Size(max = 2000)
    private String notes;
}
