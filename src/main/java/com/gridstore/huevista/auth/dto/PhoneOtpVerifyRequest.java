package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Hand back the code that was texted, and sign in. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PhoneOtpVerifyRequest {

    @NotBlank(message = "Enter your mobile number")
    @Size(max = 20, message = "That doesn't look like a mobile number")
    private String phone;

    @NotBlank(message = "Enter the code from the text")
    @Size(max = 10, message = "That code is too long")
    private String code;
}
