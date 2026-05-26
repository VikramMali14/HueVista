package com.gridstore.huevista.painter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DeclinePaintJobRequest {

    @NotBlank
    @Size(max = 500)
    private String reason;
}
