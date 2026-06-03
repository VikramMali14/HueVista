package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    // Optional retailer trial-signup fields. When shopName is present we provision a
    // RETAILER organization + a free trial subscription so AI features work immediately.
    private String shopName;
    private String city;
    private String state;
    private String phone;
    /** "starter" | "pro"/"professional" | "business" — the trial tier. */
    private String tier;
}
