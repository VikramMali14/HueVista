package com.gridstore.huevista.library.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Remove a selection of templates from the shelf, optionally with their files. */
@Data
public class DeleteTemplatesRequest {

    @NotEmpty(message = "Select at least one room to remove")
    @Size(max = 200, message = "Too many rooms selected at once")
    private List<String> templateIds;

    /**
     * Also delete the shared photo and masks. Null/false — the default — keeps them,
     * because copies already in people's accounts point at those exact files.
     */
    private Boolean purgeFiles;
}
