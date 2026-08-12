package com.gridstore.huevista.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SMS sender for mobile verification OTPs.
 *
 * <p>No delivery provider is wired: every message is written to the server log so
 * OTPs remain testable in dev without a paid account. {@code app.sms.enabled}
 * exists because it also drives the retailer verification gate
 * (ProjectAccessPolicy) — it does NOT switch on real delivery.
 *
 * <p>To send real texts, implement a provider call here and only then set
 * {@code app.sms.enabled=true} in production. Until that happens, enabling the
 * flag gates retailers behind a code that never leaves the log.
 *
 * <p>A send must never throw: a failed notification must not bubble up as a 500
 * on signup or verification.
 */
@Component
@Slf4j
public class SmsSender {

    @Value("${app.sms.enabled:false}")
    private boolean enabled;

    public void send(String phoneNumber, String text) {
        if (enabled) {
            // Flag on, but nothing implements delivery — say so loudly rather than
            // letting the caller believe a text went out.
            log.warn("app.sms.enabled=true but no SMS provider is implemented — "
                    + "the code below was NOT delivered.");
        }
        log.warn("[DEV SMS] to={} | {}", phoneNumber, text);
    }
}
