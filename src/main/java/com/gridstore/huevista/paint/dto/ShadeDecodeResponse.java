package com.gridstore.huevista.paint.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * What the counter gets back when it reads a customer's code.
 *
 * A colour board leaves the shop carrying HV codes, which say nothing about the paint
 * company or the shade — that is the point of them. This is the other half: a shop with
 * a HueVista account types the code in and gets the real colour back.
 *
 * Two things come back, and the second is the one the counter actually needs most days.
 * {@link #shade} is what the code IS. {@link #brandMatch} is what the shop can sell:
 * the customer chose a colour from whatever company the room was designed against, and
 * the shop in front of them may not stock that company at all. Naming the exact shade
 * and stopping there leaves the counter to eyeball a swatch book.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ShadeDecodeResponse {

    /** The code as it was read, normalised — echoed back so a typo is visible. */
    private String query;

    /** How it resolved: {@code HV_CODE} for a HueVista code, {@code SHADE_CODE} for a
     *  manufacturer's own. Null when nothing matched. */
    private String matchedBy;

    /** The shade the code names. Null when nothing matched, or when a bare manufacturer
     *  code matched several companies — see {@link #candidates}. */
    private ShadeResponse shade;

    /**
     * The companies a bare manufacturer code could have come from, when more than one
     * carries it. Manufacturer codes are only unique within a company, so "L124" alone
     * is a question, not an answer; the counter picks. Absent for an HV code, which is
     * unique across the whole catalogue and can never be ambiguous.
     */
    private List<ShadeResponse> candidates;

    /** The nearest shade in the company the shop asked about. Absent when none was asked. */
    private BrandMatch brandMatch;

    /**
     * The best this company can do for this colour, and how good that is.
     *
     * {@code exact} distinguishes the two answers a counter must never confuse: this
     * company sells precisely this colour, versus this is the closest thing they make.
     * Quoting the second as if it were the first is how a customer ends up with a wall
     * that is not the colour they chose.
     */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BrandMatch {
        private String brandName;
        private String brandSlug;
        private ShadeResponse shade;
        /** True only when this company carries the very colour — a zero perceptual distance. */
        private boolean exact;
        /** CIE76 ΔE between the decoded colour and this one. 0 when exact. */
        private double deltaE;
        /** That distance in words, for a counter that does not think in ΔE. */
        private String closeness;
    }
}
