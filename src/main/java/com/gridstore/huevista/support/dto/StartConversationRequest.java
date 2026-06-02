package com.gridstore.huevista.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class StartConversationRequest {
    @NotBlank(message = "A message is required")
    @Size(max = 4000)
    private String message;

    @Size(max = 200)
    private String subject;
}
