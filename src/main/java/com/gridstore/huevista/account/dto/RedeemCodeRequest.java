package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RedeemCodeRequest {

    @NotBlank
    private String code;
}
