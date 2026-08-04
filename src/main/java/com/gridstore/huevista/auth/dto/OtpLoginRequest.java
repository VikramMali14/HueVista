package com.gridstore.huevista.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;

/**
 * Second step of an admin login: the same credentials PLUS the emailed one-time
 * code. The password rides along so an intercepted code alone is useless.
 */
@Data
public class OtpLoginRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @NotBlank
    private String code;
}
