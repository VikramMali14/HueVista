package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.OrgType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateOrgRequest {

    @NotBlank
    @Size(min = 2, max = 100)
    private String name;

    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug must be lowercase alphanumeric with hyphens only")
    @Size(min = 2, max = 50)
    private String slug;

    @NotNull
    private OrgType type;
}
