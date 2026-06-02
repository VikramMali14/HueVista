package com.gridstore.huevista.paint.dto;

import com.gridstore.huevista.paint.model.Brand;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class BrandResponse {
    private Long id;
    private String name;
    private String slug;

    public static BrandResponse from(Brand b) {
        return BrandResponse.builder().id(b.getId()).name(b.getName()).slug(b.getSlug()).build();
    }
}
