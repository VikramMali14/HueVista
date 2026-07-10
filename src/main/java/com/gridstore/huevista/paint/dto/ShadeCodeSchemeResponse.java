package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.ShadeCodeScheme;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A shop's shade-code scheme. Customer code = prefix + code[0..2] + infix +
 * code[2..] + suffix. All parts are plain strings, empty when unused; a
 * response with every part empty means "no scheme" (real codes stay hidden
 * from guests, as before).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadeCodeSchemeResponse {

    private String prefix;
    private String infix;
    private String suffix;
    private LocalDateTime updatedAt;

    public static ShadeCodeSchemeResponse from(ShadeCodeScheme scheme) {
        return ShadeCodeSchemeResponse.builder()
                .prefix(scheme.getPrefix())
                .infix(scheme.getInfix())
                .suffix(scheme.getSuffix())
                .updatedAt(scheme.getUpdatedAt())
                .build();
    }

    /** The "no scheme" shape — every part empty, so clients need no null checks. */
    public static ShadeCodeSchemeResponse empty() {
        return ShadeCodeSchemeResponse.builder().prefix("").infix("").suffix("").build();
    }
}
