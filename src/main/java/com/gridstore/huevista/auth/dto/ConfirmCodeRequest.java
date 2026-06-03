package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmCodeRequest {

    @NotBlank(message = "Enter the 6-digit code")
    private String code;
}
