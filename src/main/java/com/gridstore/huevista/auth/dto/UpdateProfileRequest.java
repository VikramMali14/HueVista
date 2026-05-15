package com.gridstore.huevista.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @NotBlank
    @Size(min = 2, max = 100)
    private String name;

    private String picture;
}
