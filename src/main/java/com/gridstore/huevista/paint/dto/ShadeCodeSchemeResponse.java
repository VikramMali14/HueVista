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

    /**
     * Patterns this shop has stopped using, newest first.
     *
     * Sent so the checker can read a code printed under an older pattern — a colour board
     * from last season, a photo of the counter screen — instead of calling the shop's own
     * code invalid. Never used to ENCODE: new codes always use the live pattern above.
     */
    @Builder.Default
    private java.util.List<RetiredScheme> retired = java.util.List.of();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetiredScheme {
        private String prefix;
        private String infix;
        private String suffix;
        /** When the shop stopped using it — what dates an old card. */
        private LocalDateTime retiredAt;

        public static RetiredScheme from(com.gridstore.huevista.paint.model.RetiredShadeCodeScheme s) {
            return RetiredScheme.builder()
                    .prefix(s.getPrefix()).infix(s.getInfix()).suffix(s.getSuffix())
                    .retiredAt(s.getRetiredAt())
                    .build();
        }
    }

    public static ShadeCodeSchemeResponse from(ShadeCodeScheme scheme, boolean showNames) {
        return from(scheme, showNames, java.util.List.of());
    }

    public static ShadeCodeSchemeResponse from(ShadeCodeScheme scheme, boolean showNames,
                                               java.util.List<RetiredScheme> retired) {
        return ShadeCodeSchemeResponse.builder()
                .prefix(scheme.getPrefix())
                .infix(scheme.getInfix())
                .suffix(scheme.getSuffix())
                .showNames(showNames)
                .updatedAt(scheme.getUpdatedAt())
                .retired(retired)
                .build();
    }

    /** No pattern — every part empty, so clients need no null checks. */
    public static ShadeCodeSchemeResponse empty(boolean showNames) {
        return empty(showNames, java.util.List.of());
    }

    public static ShadeCodeSchemeResponse empty(boolean showNames,
                                                java.util.List<RetiredScheme> retired) {
        return ShadeCodeSchemeResponse.builder()
                .prefix("").infix("").suffix("").showNames(showNames).retired(retired).build();
    }

    /** No pattern and no shop to ask — names show, which is the default everywhere. */
    public static ShadeCodeSchemeResponse empty() {
        return empty(true);
    }
}
