package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Ask for a sign-in code by SMS. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PhoneOtpSendRequest {

    @NotBlank(message = "Enter your mobile number")
    @Size(max = 20, message = "That doesn't look like a mobile number")
    private String phone;

    /**
     * What to call the customer, used ONLY if this number turns out to have no account.
     * Collected on the first screen so the second one has a single field on it.
     */
    @Size(max = 100, message = "That name is too long")
    private String name;
}
