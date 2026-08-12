package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.paint.model.Brand;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * What the shop's "which companies do we show?" settings page renders.
 *
 * {@code brands} is the pool the shop may choose from — the companies its distributor
 * granted it, not the whole platform catalogue, because a company it was never assigned
 * is a checkbox that would silently do nothing.
 *
 * {@code restricted} is the shop's own switch. When it is false every option comes back
 * shown, so the page renders "all on" without the client having to know that no rows
 * means everything rather than nothing.
 */
@Data
@Builder
public class ShopBrandVisibilityResponse {

    /** True when the shop has narrowed its catalogue itself. */
    private boolean restricted;

    /** Every company the shop may show, each flagged with whether it currently is. */
    private List<Option> brands;

    @Data
    @Builder
    public static class Option {
        private Long id;
        private String name;
        private String slug;
        private boolean shown;
        /**
         * How many shades this company actually has loaded.
         *
         * A grant and a catalogue are two different things: a distributor can assign a
         * shop a company whose shades have never been imported, and until now nothing
         * said so. The shop counted its companies on one screen and its colours on
         * another and got different answers — eight companies assigned, six with any
         * colour in them — with no way to tell which two were empty. Switching an empty
         * company on shows the customer a company with nothing behind it, so the number
         * travels with the option.
         */
        private long shadeCount;
    }

    public static ShopBrandVisibilityResponse of(boolean restricted, List<Brand> grantable,
                                                 Predicate<Brand> shown,
                                                 ToLongFunction<Brand> shadeCount) {
        return ShopBrandVisibilityResponse.builder()
                .restricted(restricted)
                .brands(grantable.stream()
                        .map(b -> Option.builder()
                                .id(b.getId())
                                .name(b.getName())
                                .slug(b.getSlug())
                                .shown(shown.test(b))
                                .shadeCount(shadeCount.applyAsLong(b))
                                .build())
                        .toList())
                .build();
    }
}
