package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 8, message = "New password must be at least 8 characters")
    @jakarta.validation.constraints.Pattern(
            regexp = "^(?=.*\\p{L})(?=.*\\d).*$",
            message = "New password must contain at least one letter and one number")
    private String newPassword;
}
