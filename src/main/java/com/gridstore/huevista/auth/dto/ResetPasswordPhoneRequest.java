package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Reset a password with the 6-digit code sent by SMS to a verified mobile number. */
@Data
public class ResetPasswordPhoneRequest {

    @NotBlank(message = "Mobile number is required")
    private String phone;

    @NotBlank(message = "Enter the 6-digit code")
    private String code;

    @NotBlank(message = "New password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @jakarta.validation.constraints.Pattern(
            regexp = "^(?=.*\\p{L})(?=.*\\d).*$",
            message = "Password must contain at least one letter and one number")
    private String newPassword;
}
