package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.ShadeCodeScheme;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * How a shop presents a colour: its shade-code pattern, and whether paint names are
 * shown at all.
 *
 * Customer code = prefix + code[0..2] + infix + code[2..] + suffix. All parts are plain
 * strings, empty when unused; every part empty means "no pattern" and real codes are
 * shown as they are.
 *
 * {@code showNames} travels with the pattern because they are one decision in practice —
 * a shop hiding the paint company behind its own codes does not want the product name
 * printed beside them — and because one fetch then tells the studio everything it needs
 * to render a swatch.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadeCodeSchemeResponse {

    private String prefix;
    private String infix;
    private String suffix;
    /** False when this shop hides paint names everywhere a colour is shown. */
    @Builder.Default
    private boolean showNames = true;
    private LocalDateTime updatedAt;

    public static ShadeCodeSchemeResponse from(ShadeCodeScheme scheme, boolean showNames) {
        return ShadeCodeSchemeResponse.builder()
                .prefix(scheme.getPrefix())
                .infix(scheme.getInfix())
                .suffix(scheme.getSuffix())
                .showNames(showNames)
                .updatedAt(scheme.getUpdatedAt())
                .build();
    }

    /** No pattern — every part empty, so clients need no null checks. */
    public static ShadeCodeSchemeResponse empty(boolean showNames) {
        return ShadeCodeSchemeResponse.builder()
                .prefix("").infix("").suffix("").showNames(showNames).build();
    }

    /** No pattern and no shop to ask — names show, which is the default everywhere. */
    public static ShadeCodeSchemeResponse empty() {
        return empty(true);
    }
}
