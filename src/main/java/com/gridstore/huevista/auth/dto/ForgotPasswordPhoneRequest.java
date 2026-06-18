package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Request an SMS password-reset code to a verified mobile number. */
@Data
public class ForgotPasswordPhoneRequest {

    @NotBlank(message = "Mobile number is required")
    private String phone;
}
