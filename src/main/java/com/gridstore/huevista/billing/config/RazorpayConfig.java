package com.gridstore.huevista.billing.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RazorpayConfig {

    @Value("${razorpay.key-id:}")
    private String keyId;

    @Value("${razorpay.key-secret:}")
    private String keySecret;

    @Bean
    public RazorpayClient razorpayClient() throws RazorpayException {
        if (keyId.isBlank() || keySecret.isBlank()) {
            log.warn("Razorpay credentials not configured — billing features will be unavailable");
            // Return a client with empty credentials; calls will fail at runtime with clear messages
            return new RazorpayClient("", "");
        }
        return new RazorpayClient(keyId, keySecret);
    }
}
