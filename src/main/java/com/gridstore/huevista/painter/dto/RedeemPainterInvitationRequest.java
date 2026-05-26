package com.gridstore.huevista.painter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RedeemPainterInvitationRequest {

    @NotBlank
    private String code;
}
