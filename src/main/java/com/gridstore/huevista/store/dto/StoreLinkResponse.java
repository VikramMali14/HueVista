package com.gridstore.huevista.store.dto;

import com.gridstore.huevista.store.model.StoreLink;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class StoreLinkResponse {
    private String id;
    private String slug;
    private String organizationId;
    private String organizationName;
    private int pricePaise;
    private String currency;
    private int validDays;
    private boolean active;
    private LocalDateTime createdAt;

    public static StoreLinkResponse from(StoreLink link) {
        return StoreLinkResponse.builder()
                .id(link.getId())
                .slug(link.getSlug())
                .organizationId(link.getOrganization().getId())
                .organizationName(link.getOrganization().getName())
                .pricePaise(link.getPricePaise())
                .currency("INR")
                .validDays(link.getValidDays())
                .active(link.isActive())
                .createdAt(link.getCreatedAt())
                .build();
    }
}
