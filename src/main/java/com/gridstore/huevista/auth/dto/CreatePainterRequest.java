package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Retailer-only: create a PAINTER account linked to the retailer's shop. */
@Data
public class CreatePainterRequest {

    @NotBlank(message = "Painter name is required")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "An initial password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    private String phone;
}
