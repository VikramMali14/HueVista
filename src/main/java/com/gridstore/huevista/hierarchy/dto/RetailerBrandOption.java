package com.gridstore.huevista.hierarchy.dto;

import com.gridstore.huevista.paint.model.Brand;
import lombok.Builder;
import lombok.Data;

/**
 * One paint brand a distributor could grant to a shop, with whether it is
 * currently assigned. The distributor's brand-assignment editor renders one row
 * per option so a single call fills the whole checklist.
 */
@Data
@Builder
public class RetailerBrandOption {

    private Long id;
    private String name;
    private String slug;
    private boolean assigned;

    public static RetailerBrandOption of(Brand b, boolean assigned) {
        return RetailerBrandOption.builder()
                .id(b.getId())
                .name(b.getName())
                .slug(b.getSlug())
                .assigned(assigned)
                .build();
    }
}
