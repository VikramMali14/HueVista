package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** The emailed sign-in code, with the address it was sent to. */
@Data
public class KioskReentryConfirmRequest {

    @NotBlank
    @Size(max = 320)
    private String email;

    @NotBlank
    @Size(max = 12)
    private String code;
}
