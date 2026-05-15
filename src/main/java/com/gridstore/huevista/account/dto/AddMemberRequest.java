package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.account.model.OrgMemberRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddMemberRequest {

    @NotBlank
    private String userId;

    @NotNull
    private OrgMemberRole role;
}
