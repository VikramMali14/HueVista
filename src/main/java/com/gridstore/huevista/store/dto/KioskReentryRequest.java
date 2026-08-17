package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** "Email me a way back into the room I bought." */
@Data
public class KioskReentryRequest {

    @NotBlank
    @Email
    @Size(max = 320)
    private String email;
}
