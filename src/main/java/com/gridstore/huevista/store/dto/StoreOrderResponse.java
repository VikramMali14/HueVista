package com.gridstore.huevista.store.dto;

import lombok.Builder;
import lombok.Data;

/** A Razorpay order the kiosk opens in Checkout (UPI / QR included). */
@Data
@Builder
public class StoreOrderResponse {
    private String orderId;
    private int amount; // paise
    private String currency;
    private String razorpayKeyId;
    private String shopName;
}
