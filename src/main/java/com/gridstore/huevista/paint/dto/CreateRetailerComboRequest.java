package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.ComboScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRetailerComboRequest {

    @NotBlank(message = "Give the combination a name.")
    @Size(max = 80, message = "Keep the name under 80 characters.")
    private String name;

    @NotNull(message = "Pick interior or exterior.")
    private ComboScope scope;

    /** Exactly three shades, in studio role order: main wall, accent wall, trim. */
    @NotNull(message = "Pick three shades.")
    @Size(min = 3, max = 3, message = "A combination is exactly three shades.")
    @Valid
    private List<ComboShadeDto> shades;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComboShadeDto {
        @NotBlank(message = "Every shade needs a code.")
        @Size(max = 40)
        private String code;

        @NotBlank(message = "Every shade needs a name.")
        @Size(max = 120)
        private String name;

        @NotBlank(message = "Every shade needs a hex colour.")
        @Pattern(regexp = "#[0-9a-fA-F]{6}", message = "Hex colours look like #a47148.")
        private String hex;
    }
}
