package com.gridstore.huevista.account.dto;

import com.gridstore.huevista.paint.model.Brand;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.function.Predicate;

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
    }

    public static ShopBrandVisibilityResponse of(boolean restricted, List<Brand> grantable,
                                                 Predicate<Brand> shown) {
        return ShopBrandVisibilityResponse.builder()
                .restricted(restricted)
                .brands(grantable.stream()
                        .map(b -> Option.builder()
                                .id(b.getId())
                                .name(b.getName())
                                .slug(b.getSlug())
                                .shown(shown.test(b))
                                .build())
                        .toList())
                .build();
    }
}
