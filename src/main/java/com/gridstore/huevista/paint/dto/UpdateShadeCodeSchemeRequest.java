package com.gridstore.huevista.paint.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sets (or clears) the shop's shade-code scheme. Letters and digits only —
 * these parts get spliced into real shade codes, so anything else would make
 * the customer code ambiguous to read back. Null and "" both mean "no part";
 * all three empty clears the scheme.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateShadeCodeSchemeRequest {

    @Size(max = 4, message = "Prefix can be at most 4 characters.")
    @Pattern(regexp = "[A-Za-z0-9]*", message = "Prefix can only use letters and digits.")
    private String prefix;

    @Size(max = 2, message = "The inserted pair can be at most 2 characters.")
    @Pattern(regexp = "[A-Za-z0-9]*", message = "The inserted pair can only use letters and digits.")
    private String infix;

    @Size(max = 4, message = "Suffix can be at most 4 characters.")
    @Pattern(regexp = "[A-Za-z0-9]*", message = "Suffix can only use letters and digits.")
    private String suffix;
}
