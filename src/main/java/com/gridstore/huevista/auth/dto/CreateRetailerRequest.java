package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Admin-only: create a RETAILER (shop) account with a provisioned org + trial. */
@Data
public class CreateRetailerRequest {

    @NotBlank(message = "Owner name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "An initial password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Shop name is required")
    private String shopName;

    private String city;
    private String state;
    private String phone;
    /** "starter" | "pro"/"professional" | "business" — the shop's plan tier. */
    private String tier;
}
