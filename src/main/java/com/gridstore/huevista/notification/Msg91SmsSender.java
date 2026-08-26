package com.gridstore.huevista.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Real SMS delivery through MSG91's Flow API.
 *
 * <h2>What this needs before it can work</h2>
 * A DLT registration. There is no way around it and no provider can sell you one: TRAI
 * requires every sender to register its entity, its header (sender id) and every message
 * template on a DLT platform before an Indian operator will carry the message. MSG91 is
 * the transport, not an exemption. Until that is in place this class is not selected —
 * see {@link SmsConfig} — and the codes go to the log instead.
 *
 * <h2>Why the Flow API and not MSG91's own OTP API</h2>
 * MSG91 will happily generate, store and verify the OTP itself. We do not let it, because
 * this codebase already does that properly — codes are BCrypt-hashed at rest, single-use,
 * expiring, cooldown-throttled and attempt-limited, and that logic is tested. Handing the
 * OTP lifecycle to a vendor would move the security boundary off our servers, add a
 * network round trip to every verification, and make the provider expensive to change.
 * Here MSG91 is a pipe: it carries a code it never gets to keep.
 *
 * <h2>Two failure modes worth knowing about</h2>
 * MSG91 answers {@code HTTP 200} with {@code "type":"error"} in the body for a rejected
 * template, an unregistered variable or an exhausted balance, so the status line alone is
 * not the answer — the body is parsed. And a template mismatch is silent at the operator:
 * MSG91 accepts the request, the handset never rings. If codes stop arriving with no
 * error here, the DLT template and the variables below are the first thing to check.
 */
@Slf4j
public class Msg91SmsSender implements SmsSender {

    public static final String FLOW_URL = "https://control.msg91.com/api/v5/flow";

    private final String authKey;
    private final String templateId;
    private final String otpVariable;
    private final String validityVariable;
    private final String senderId;
    private final String flowUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public Msg91SmsSender(String authKey, String templateId, String otpVariable,
                          String validityVariable, String senderId) {
        this(authKey, templateId, otpVariable, validityVariable, senderId, FLOW_URL);
    }

    /**
     * @param flowUrl where the request goes. Overridable only so the tests can drive a
     *                stand-in and assert on the exact request shape MSG91 receives —
     *                which is worth pinning, because a wrong variable name or number
     *                format is ACCEPTED by MSG91 and then dropped silently by the
     *                operator. No deployment should ever pass anything but the default.
     */
    Msg91SmsSender(String authKey, String templateId, String otpVariable,
                   String validityVariable, String senderId, String flowUrl) {
        this.flowUrl = flowUrl;
        this.authKey = authKey;
        this.templateId = templateId;
        this.otpVariable = otpVariable;
        this.validityVariable = validityVariable;
        this.senderId = senderId == null || senderId.isBlank() ? null : senderId.trim();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public boolean deliversForReal() {
        return true;
    }

    @Override
    public void sendOtp(String phoneNumber, String code, int validMinutes) {
        String mobile = msg91Number(phoneNumber);
        if (mobile == null) {
            log.warn("Not sending an SMS to an unusable number");
            return;
        }
        try {
            ObjectNode recipient = mapper.createObjectNode();
            recipient.put("mobiles", mobile);
            recipient.put(otpVariable, code);
            recipient.put(validityVariable, String.valueOf(validMinutes));

            ObjectNode body = mapper.createObjectNode();
            body.put("template_id", templateId);
            // The panel maps a sender to the template; this only overrides it where a
            // deployment deliberately uses a second registered header.
            if (senderId != null) body.put("sender", senderId);
            body.putArray("recipients").add(recipient);

            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(flowUrl))
                            .timeout(Duration.ofSeconds(10))
                            .header("authkey", authKey)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (!accepted(response)) {
                // The code itself is never logged — a server log is not a place to put
                // something that grants a session.
                log.error("MSG91 refused an OTP to {}: HTTP {} {}",
                        masked(mobile), response.statusCode(), response.body());
                return;
            }
            log.info("OTP texted to {} via MSG91", masked(mobile));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted texting an OTP to {}", masked(mobile));
        } catch (Exception e) {
            // Never throws: a failed notification must not become a 500 on the flow that
            // triggered it. The caller's own cooldown lets the user simply ask again.
            log.error("Could not text an OTP to {}: {}", masked(mobile), e.toString());
        }
    }

    /** MSG91 reports failure in the body as often as in the status line. */
    private boolean accepted(HttpResponse<String> response) {
        if (response.statusCode() != 200) return false;
        try {
            String type = mapper.readTree(response.body()).path("type").asText("");
            return !"error".equalsIgnoreCase(type);
        } catch (Exception e) {
            // A 200 we cannot parse is not evidence of failure; the send most likely
            // went through and the caller can do nothing useful with a guess either way.
            return true;
        }
    }

    /**
     * MSG91 wants country code and digits with no {@code +} — {@code 919876543210}.
     *
     * <p>A number with no country code would be sent as-is and silently misrouted, so
     * anything that does not carry one is refused rather than guessed at. Every number
     * this app stores has been through {@code PhoneNumbers.normalize} first.
     */
    private static String msg91Number(String phoneNumber) {
        if (phoneNumber == null) return null;
        String digits = phoneNumber.replaceAll("[^0-9]", "");
        return digits.length() >= 10 && digits.length() <= 15 ? digits : null;
    }

    private static String masked(String mobile) {
        int keep = Math.min(3, mobile.length());
        return "*".repeat(Math.max(0, mobile.length() - keep)) + mobile.substring(mobile.length() - keep);
    }
}
