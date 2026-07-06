package com.gridstore.huevista.project.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Partial update of a project's descriptive fields. Only non-null fields are
 * applied; a provided-but-blank name is rejected (a project must stay findable
 * on the dashboard by its name).
 */
@Data
public class UpdateProjectRequest {

    @Size(max = 200, message = "Name must be at most 200 characters")
    private String name;

    @Size(max = 100, message = "Room type must be at most 100 characters")
    private String roomType;

    @Size(max = 2000, message = "Notes must be at most 2000 characters")
    private String notes;
}
