package com.gridstore.huevista.project.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateProjectRequest {

    @NotBlank
    private String imageId;

    private String name; // optional — defaults to "Project N" if blank
}
