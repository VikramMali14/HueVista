package com.gridstore.huevista.lead.dto;

import com.gridstore.huevista.lead.model.ShopLead;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ShopLeadResponse {
    private String id;
    private String name;
    private String email;
    private String phone;
    private String shopName;
    private String city;
    private String state;
    private String tier;
    private String notes;
    private ShopLead.Status status;
    private LocalDateTime createdAt;

    public static ShopLeadResponse from(ShopLead lead) {
        return ShopLeadResponse.builder()
                .id(lead.getId())
                .name(lead.getName())
                .email(lead.getEmail())
                .phone(lead.getPhone())
                .shopName(lead.getShopName())
                .city(lead.getCity())
                .state(lead.getState())
                .tier(lead.getTier())
                .notes(lead.getNotes())
                .status(lead.getStatus())
                .createdAt(lead.getCreatedAt())
                .build();
    }
}
