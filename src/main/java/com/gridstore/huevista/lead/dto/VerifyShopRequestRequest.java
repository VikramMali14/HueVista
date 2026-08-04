package com.gridstore.huevista.lead.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** The 6-digit code emailed to a shop-account requester. */
@Data
public class VerifyShopRequestRequest {

    @NotBlank(message = "Enter the code we emailed you")
    @Size(max = 12)
    private String code;
}
