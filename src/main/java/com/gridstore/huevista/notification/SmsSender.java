package com.gridstore.huevista.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Pluggable SMS sender for mobile verification OTPs.
 *
 * <p>Default ("console") just logs the message to the server log so OTPs are
 * testable without a paid provider. To send real texts in prod set
 * {@code app.sms.enabled=true} and {@code app.sms.provider=twilio} (plus the
 * three Twilio creds below) — messages then go out via the Twilio REST API.
 *
 * <p>An <em>enabled</em> provider that is unknown, or Twilio with missing creds,
 * falls back to console with a warning: a verification code must never be
 * silently dropped, and a failed send must never bubble up as a 500.
 */
@Component
@Slf4j
public class SmsSender {

    private static final String TWILIO_API_BASE = "https://api.twilio.com/2010-04-01";

    private final RestTemplate restTemplate;

    @Value("${app.sms.enabled:false}")
    private boolean enabled;

    @Value("${app.sms.provider:console}")
    private String provider;

    // Twilio — used when app.sms.provider=twilio. Creds at https://console.twilio.com
    @Value("${twilio.account-sid:}")
    private String twilioAccountSid;

    @Value("${twilio.auth-token:}")
    private String twilioAuthToken;

    // Sender number (or Messaging Service SID) in E.164, e.g. +14155552671.
    @Value("${twilio.from-number:}")
    private String twilioFromNumber;

    public SmsSender(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public void send(String phoneNumber, String text) {
        if (!enabled || "console".equalsIgnoreCase(provider)) {
            logToConsole(phoneNumber, text);
            return;
        }
        if ("twilio".equalsIgnoreCase(provider)) {
            sendViaTwilio(phoneNumber, text);
            return;
        }
        // Enabled but the provider name isn't one we wire — surface the code, don't drop it.
        log.warn("SMS provider '{}' is enabled but not wired yet — falling back to console.", provider);
        logToConsole(phoneNumber, text);
    }

    /**
     * Send via the Twilio REST API: a form-encoded POST authenticated with HTTP
     * Basic (Account SID + Auth Token). Uses the shared RestTemplate so the
     * connect/read timeouts in AppConfig apply. Failures are logged, not thrown —
     * a verification SMS that can't be delivered must not fail the signup/verify call.
     */
    private void sendViaTwilio(String phoneNumber, String text) {
        if (!notBlank(twilioAccountSid) || !notBlank(twilioAuthToken) || !notBlank(twilioFromNumber)) {
            log.warn("Twilio SMS enabled but creds are incomplete "
                    + "(twilio.account-sid / auth-token / from-number) — falling back to console.");
            logToConsole(phoneNumber, text);
            return;
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setBasicAuth(twilioAccountSid, twilioAuthToken);

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("To", phoneNumber);
            form.add("From", twilioFromNumber);
            form.add("Body", text);

            restTemplate.postForObject(
                    TWILIO_API_BASE + "/Accounts/" + twilioAccountSid + "/Messages.json",
                    new HttpEntity<>(form, headers), String.class);
            log.debug("Twilio SMS dispatched to {}", phoneNumber);
        } catch (Exception e) {
            log.warn("Twilio SMS send failed for {}: {}", phoneNumber, e.getMessage());
        }
    }

    private void logToConsole(String phoneNumber, String text) {
        // DEV / unconfigured: surface the message so the code is testable.
        log.warn("[DEV SMS] to={} | {}", phoneNumber, text);
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
