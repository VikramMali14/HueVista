package com.gridstore.huevista.support.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Sends WhatsApp messages via the Meta WhatsApp Cloud API. Config-gated: a no-op
 * (returns false) until whatsapp.access-token + whatsapp.phone-number-id are set.
 *
 * Setup: a Meta WhatsApp Business account → a phone number id + a permanent
 * access token, plus a webhook verify token. UNTESTED until those are provided.
 */
@Slf4j
@Service
public class WhatsAppService {

    private final RestTemplate restTemplate;

    @Value("${whatsapp.api-base:https://graph.facebook.com/v21.0}")
    private String apiBase;

    @Value("${whatsapp.access-token:}")
    private String accessToken;

    @Value("${whatsapp.phone-number-id:}")
    private String phoneNumberId;

    @Value("${whatsapp.verify-token:}")
    private String verifyToken;

    public WhatsAppService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isEnabled() {
        return notBlank(accessToken) && notBlank(phoneNumberId);
    }

    public boolean verifyTokenMatches(String token) {
        return notBlank(verifyToken) && verifyToken.equals(token);
    }

    /** Send a plain text WhatsApp message. Returns true if dispatched. */
    public boolean sendText(String toPhone, String text) {
        if (!isEnabled() || toPhone == null || text == null || text.isBlank()) return false;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken);
            Map<String, Object> body = Map.of(
                    "messaging_product", "whatsapp",
                    "to", toPhone,
                    "type", "text",
                    "text", Map.of("body", text)
            );
            restTemplate.postForObject(
                    apiBase + "/" + phoneNumberId + "/messages",
                    new HttpEntity<>(body, headers), Map.class);
            return true;
        } catch (Exception e) {
            log.warn("WhatsApp send failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
