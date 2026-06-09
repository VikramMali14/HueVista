package com.gridstore.huevista.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body for claiming guest-created projects into a freshly signed-up account. */
@Data
public class GuestLinkRequest {

    @NotBlank
    private String guestToken;
}
