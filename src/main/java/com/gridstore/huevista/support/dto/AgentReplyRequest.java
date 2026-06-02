package com.gridstore.huevista.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A human agent's reply (staff inbox). */
@Data
public class AgentReplyRequest {
    @NotBlank(message = "A reply is required")
    @Size(max = 4000)
    private String body;
}
