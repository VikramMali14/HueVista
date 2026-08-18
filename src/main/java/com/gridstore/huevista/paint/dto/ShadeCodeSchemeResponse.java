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
    /**
     * Whether the paint COMPANY may be printed against an individual shade.
     *
     * False for every customer and guest, always — not a per-shop switch like
     * {@code showNames}. A shade's identity is company plus name plus code, and hiding
     * two of the three while printing "Asian Paints" on the swatch leaves a colour that
     * one phone call to that company's helpline resolves. The scheme only means anything
     * if all three go together.
     *
     * <p>This is NOT a rule about the company as a filter. A customer still chooses
     * which companies they are looking at — they have to, since they will be buying from
     * a shop that stocks some and not others — and the picker is expected to name them.
     * What goes is the per-shade attribution: a colour is a swatch and a code, and the
     * counter is where those turn back into a tin.
     *
     * <p>True for shop staff and administrators, who are the people opening the tin.
     */
    @Builder.Default
    private boolean showBrands = true;
    /**
     * Whether this viewer may see the manufacturer's own shade codes.
     *
     * True for shop staff and administrators — the people who have to open the right
     * tin. False for everyone else, and for them the client shows the platform-wide HV
     * code instead: a customer's screen, their colour board and any link they forward
     * carry a number that names no company and no shade, and that only a HueVista shop
     * can turn back into a colour.
     *
     * Defaults to FALSE so a client that cannot resolve a viewer, or a response built
     * by a path that has not thought about this, withholds rather than leaks. The
     * expensive mistake here is one-directional: showing a shop an HV code costs them
     * one lookup, while showing a customer the manufacturer's code hands away the thing
     * the whole scheme exists to keep.
     */
    @Builder.Default
    private boolean showRealCodes = false;
    private LocalDateTime updatedAt;
    /**
     * When this shop first set up customer codes at all.
     *
     * The anchor the checker's history hangs off: every pattern's active window runs
     * from the moment the one before it was retired, and the OLDEST pattern has
     * nothing before it — without this its window would have to start at "unknown"
     * and the whole timeline would read as guesswork.
     */
    private LocalDateTime firstSetAt;

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

    /**
     * Set who this response is for, after the fact.
     *
     * A fluent setter rather than a sixth parameter on each of the five factories below:
     * the viewer is resolved in one place ({@code ShadeCodeSchemeService#describe}) and
     * every factory funnels through it, so threading it through all of them would add an
     * argument to each caller to say the same thing once.
     */
    public ShadeCodeSchemeResponse forViewer(boolean showRealCodes) {
        this.showRealCodes = showRealCodes;
        // One decision, not three. Whoever may not read the manufacturer's code may not
        // read the company or the shade name either — those identify the colour just as
        // completely, and a screen that withholds the number while printing "Asian
        // Paints · Wine Sensation" has withheld nothing at all. Applied here, at the one
        // point every factory funnels through, so no path can set two of the three and
        // quietly leak by the third.
        if (!showRealCodes) {
            this.showNames = false;
            this.showBrands = false;
        }
        return this;
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
                .firstSetAt(scheme.getCreatedAt())
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
