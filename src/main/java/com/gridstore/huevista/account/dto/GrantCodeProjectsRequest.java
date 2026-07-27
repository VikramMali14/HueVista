package com.gridstore.huevista.account.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/** How many more projects to add to a code the shop has already issued. */
@Data
public class GrantCodeProjectsRequest {

    @Min(value = 1, message = "Add at least 1 project")
    @Max(value = 20, message = "At most 20 projects can be added at a time")
    private int projects = 1;
}
