package com.gridstore.huevista.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pluggable SMS sender. In dev (the default) it logs the message to the server
 * console so OTPs are testable without a paid SMS provider. To send real texts in
 * prod, set {@code app.sms.enabled=true} and {@code app.sms.provider=<name>} and
 * wire the provider call at the marked extension point (e.g. Twilio / MSG91).
 */
@Component
@Slf4j
public class SmsSender {

    @Value("${app.sms.enabled:false}")
    private boolean enabled;

    @Value("${app.sms.provider:console}")
    private String provider;

    public void send(String phoneNumber, String text) {
        if (enabled && !"console".equalsIgnoreCase(provider)) {
            // EXTENSION POINT: plug a real SMS provider here, e.g.
            //   if ("twilio".equals(provider)) twilioClient.send(phoneNumber, text);
            // Until one is wired we must not silently drop the message.
            log.warn("SMS provider '{}' is enabled but not wired yet — falling back to console. to={} | {}",
                    provider, phoneNumber, text);
        } else {
            // DEV / unconfigured: surface the message so the code is testable.
            log.warn("[DEV SMS] to={} | {}", phoneNumber, text);
        }
    }
}
