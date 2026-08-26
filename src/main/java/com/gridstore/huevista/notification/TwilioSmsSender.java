package com.gridstore.huevista.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real SMS delivery through Twilio's Messages API.
 *
 * <h2>Twilio does not exempt you from DLT</h2>
 * It is worth being blunt about this, because it is the thing people assume. DLT is a
 * TRAI rule about the Indian phone network, not a feature of any one provider: your
 * entity, your header and every message template must be registered before an Indian
 * operator will carry the message. Twilio carries it, Twilio does not waive it. The two
 * DLT identifiers below are passed on every send precisely because Twilio has to hand
 * them to the operator.
 *
 * <h2>Why not Twilio Verify</h2>
 * Twilio will generate, store and check the OTP for you through its Verify product. We
 * do not use it, for the same reason we do not use MSG91's: this codebase already does
 * that properly — codes are BCrypt-hashed at rest, single-use, expiring,
 * cooldown-throttled and attempt-limited, and that logic is tested. Handing the OTP
 * lifecycle to a vendor moves the security boundary off our servers, adds a network
 * round trip to every verification, and prices per verification rather than per message.
 * Here Twilio is a pipe: it carries a code it never gets to keep.
 *
 * <h2>The failure worth knowing about</h2>
 * Twilio accepts the request and reports delivery asynchronously, so a {@code 201} here
 * means "queued", not "delivered". A DLT template mismatch is rejected later by the
 * operator and shows up only in Twilio's own logs — the handset simply never rings. If
 * codes stop arriving with nothing failing here, the registered template is the first
 * thing to check, character for character.
 */
@Slf4j
public class TwilioSmsSender implements SmsSender {

    private static final String API_BASE = "https://api.twilio.com/2010-04-01/Accounts/";

    private final String accountSid;
    private final String authToken;
    private final String sender;
    private final String messagingServiceSid;
    private final String dltEntityId;
    private final String dltTemplateId;
    private final String messageTemplate;
    private final String apiBase;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TwilioSmsSender(String accountSid, String authToken, String sender,
                           String messagingServiceSid, String dltEntityId, String dltTemplateId,
                           String messageTemplate) {
        this(accountSid, authToken, sender, messagingServiceSid, dltEntityId, dltTemplateId,
                messageTemplate, API_BASE);
    }

    /**
     * @param apiBase where the request goes. Overridable only so the tests can drive a
     *                stand-in and assert on the exact request shape Twilio receives.
     *                No deployment should pass anything but the default.
     */
    TwilioSmsSender(String accountSid, String authToken, String sender,
                    String messagingServiceSid, String dltEntityId, String dltTemplateId,
                    String messageTemplate, String apiBase) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.sender = blankToNull(sender);
        this.messagingServiceSid = blankToNull(messagingServiceSid);
        this.dltEntityId = blankToNull(dltEntityId);
        this.dltTemplateId = blankToNull(dltTemplateId);
        this.messageTemplate = messageTemplate;
        this.apiBase = apiBase;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public boolean deliversForReal() {
        return true;
    }

    @Override
    public void sendOtp(String phoneNumber, String code, int validMinutes) {
        String to = e164(phoneNumber);
        if (to == null) {
            log.warn("Not sending an SMS to an unusable number");
            return;
        }
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("To", to);
            // A messaging service wins when configured: it is where a registered Indian
            // sender id lives, and Twilio picks the right route from it.
            if (messagingServiceSid != null) {
                form.put("MessagingServiceSid", messagingServiceSid);
            } else if (sender != null) {
                form.put("From", sender);
            }
            form.put("Body", body(code, validMinutes));
            // The DLT identifiers Twilio hands the Indian operator. Without them the
            // operator drops the message; with the wrong ones, likewise.
            if (dltEntityId != null) form.put("DltEntityId", dltEntityId);
            if (dltTemplateId != null) form.put("DltTemplateId", dltTemplateId);

            String credentials = Base64.getEncoder().encodeToString(
                    (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(apiBase + accountSid + "/Messages.json"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Basic " + credentials)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(urlEncoded(form), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() / 100 != 2) {
                // The code itself is never logged — a server log is not a place to put
                // something that grants a session.
                log.error("Twilio refused an OTP to {}: HTTP {} {}",
                        masked(to), response.statusCode(), response.body());
                return;
            }
            log.info("OTP queued to {} via Twilio ({})", masked(to), statusOf(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted texting an OTP to {}", masked(to));
        } catch (Exception e) {
            // Never throws: a failed notification must not become a 500 on the flow that
            // triggered it. The caller's own cooldown lets the user simply ask again.
            log.error("Could not text an OTP to {}: {}", masked(to), e.toString());
        }
    }

    /**
     * The message body, built from the configured template.
     *
     * <p>Under DLT the words are not ours to choose — they are whatever was registered —
     * so the template is configuration, and it must match the registered one character
     * for character. {@code {code}} and {@code {minutes}} are the two blanks.
     */
    private String body(String code, int validMinutes) {
        return messageTemplate
                .replace("{code}", code)
                .replace("{minutes}", String.valueOf(validMinutes));
    }

    /** Twilio's own view of what happened, for the log line. */
    private String statusOf(String responseBody) {
        try {
            JsonNode node = mapper.readTree(responseBody);
            // "queued" is the normal answer. Delivery is reported later, out of band —
            // a 2xx here is not proof the handset rang.
            return node.path("status").asText("accepted");
        } catch (Exception e) {
            return "accepted";
        }
    }

    /**
     * Twilio wants full E.164 WITH the plus — {@code +919876543210}, the opposite of
     * MSG91's format. A number with no country code is refused rather than guessed at:
     * guessing would occasionally reach a real stranger, and we would pay for it.
     */
    private static String e164(String phoneNumber) {
        if (phoneNumber == null) return null;
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        return digits.length() >= 10 && digits.length() <= 15 ? "+" + digits : null;
    }

    private static String urlEncoded(Map<String, String> form) {
        StringBuilder out = new StringBuilder();
        form.forEach((key, value) -> {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
               .append('=')
               .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String masked(String number) {
        int keep = Math.min(3, number.length());
        return "*".repeat(Math.max(0, number.length() - keep)) + number.substring(number.length() - keep);
    }
}
