package com.gridstore.huevista.auth.dto;

import com.gridstore.huevista.auth.model.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChangeRoleRequest {

    @NotNull
    private UserRole role;
}
