package com.gridstore.huevista.store.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What an anonymous kiosk visitor may know about a store link: the shop's name
 * and the price. Never the org id, wallet numbers or the retailer's identity.
 */
@Data
@Builder
public class StorePublicInfoResponse {
    private String slug;
    private String shopName;
    private int pricePaise;
    private String currency;
    private int validDays;
    private boolean active;
    /** False when Razorpay keys aren't configured — the page shows "pay at the counter". */
    private boolean paymentsConfigured;
}
