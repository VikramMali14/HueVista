package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** The one-time code from the OAuth2 callback URL, traded for real tokens. */
@Data
public class OAuthExchangeRequest {

    @NotBlank
    private String code;
}
