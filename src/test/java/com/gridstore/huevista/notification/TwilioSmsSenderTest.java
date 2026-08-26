package com.gridstore.huevista.notification;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The Twilio client, driven against a stand-in for Twilio.
 *
 * <p>What is worth pinning is the request SHAPE — above all the two DLT identifiers,
 * because without them an Indian operator drops the message and NOTHING reports a
 * failure: Twilio returns a cheerful 201 and the handset never rings. These tests are
 * the only place that is checked.
 *
 * <p>And the rule that a send never throws: a notification that could not go out must
 * not surface as a 500 on the signup that triggered it.
 */
class TwilioSmsSenderTest {

    private static final String SID = "ACtest00000000000000000000000000";
    private static final String TOKEN = "test-auth-token";
    private static final String TEMPLATE =
            "{code} is your HueVista verification code. It is valid for {minutes} minutes. "
                    + "Do not share this code with anyone.";

    private HttpServer server;
    private final AtomicReference<Map<String, String>> lastForm = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Integer> statuses = new ArrayList<>();
    private final AtomicReference<String> body =
            new AtomicReference<>("{\"sid\":\"SM123\",\"status\":\"queued\"}");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().getPath());
            lastForm.set(parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            int status = statuses.isEmpty() ? 201 : statuses.remove(0);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream o = exchange.getResponseBody()) { o.write(out); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private TwilioSmsSender sender(String from, String messagingService, String entity, String template) {
        return new TwilioSmsSender(SID, TOKEN, from, messagingService, entity, template, TEMPLATE, base());
    }

    @Test
    void sends_the_number_body_and_BOTH_dlt_ids_twilio_needs_for_india() {
        sender("+15550001111", null, "1701160000000000000", "1707160000000000000")
                .sendOtp("+919876543210", "482913", 10);

        Map<String, String> form = lastForm.get();
        // Full E.164 WITH the plus — the opposite of MSG91's format.
        assertThat(form.get("To")).isEqualTo("+919876543210");
        assertThat(form.get("From")).isEqualTo("+15550001111");
        assertThat(form.get("Body")).isEqualTo(
                "482913 is your HueVista verification code. It is valid for 10 minutes. "
                        + "Do not share this code with anyone.");
        // THE thing that makes an Indian send work. Missing, the operator drops the
        // message and Twilio still answers 201 — a failure with nothing to look at.
        assertThat(form.get("DltEntityId")).isEqualTo("1701160000000000000");
        assertThat(form.get("DltTemplateId")).isEqualTo("1707160000000000000");
    }

    @Test
    void authenticates_with_the_account_sid_and_token_and_posts_to_that_account() {
        sender("+15550001111", null, "e", "t").sendOtp("+919876543210", "1", 5);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((SID + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThat(lastAuth.get()).isEqualTo(expected);
        assertThat(lastPath.get()).isEqualTo("/" + SID + "/Messages.json");
    }

    @Test
    void prefers_a_messaging_service_over_a_bare_from_number() {
        // The messaging service is where a registered Indian sender id lives, so when
        // both are set it has to win — a bare From would route around it.
        sender("+15550001111", "MGtest0000000000000000000000000", "e", "t")
                .sendOtp("+919876543210", "1", 5);

        assertThat(lastForm.get().get("MessagingServiceSid")).isEqualTo("MGtest0000000000000000000000000");
        assertThat(lastForm.get()).doesNotContainKey("From");
    }

    @Test
    void substitutes_both_blanks_in_the_registered_template() {
        // The words come from the DLT registration, so the template is configuration and
        // only these two blanks may change.
        sender("+15550001111", null, "e", "t").sendOtp("+919876543210", "000111", 7);

        assertThat(lastForm.get().get("Body"))
                .startsWith("000111 is your HueVista")
                .contains("valid for 7 minutes");
    }

    @Test
    void omits_the_dlt_ids_when_they_are_not_configured_rather_than_sending_blanks() {
        // A blank DltEntityId is worse than none: it is a value Twilio forwards, and an
        // operator rejects, where absent at least behaves predictably outside India.
        sender("+15550001111", null, null, null).sendOtp("+15551234567", "1", 5);

        assertThat(lastForm.get()).doesNotContainKey("DltEntityId");
        assertThat(lastForm.get()).doesNotContainKey("DltTemplateId");
    }

    @Test
    void survives_twilio_rejecting_the_request() {
        statuses.add(400);
        body.set("{\"code\":21606,\"message\":\"The From number is not a valid sender\"}");

        assertThatCode(() -> sender("+1", null, "e", "t").sendOtp("+919876543210", "482913", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void survives_bad_credentials() {
        statuses.add(401);
        body.set("{\"code\":20003,\"message\":\"Authentication Error\"}");

        assertThatCode(() -> sender("+15550001111", null, "e", "t").sendOtp("+919876543210", "1", 5))
                .doesNotThrowAnyException();
    }

    @Test
    void survives_twilio_being_unreachable() {
        server.stop(0);

        // A signup must not fail with a 500 because a notification could not go out.
        assertThatCode(() -> sender("+15550001111", null, "e", "t").sendOtp("+919876543210", "1", 5))
                .doesNotThrowAnyException();
    }

    @Test
    void refuses_a_number_with_no_country_code_rather_than_guessing_one() {
        sender("+15550001111", null, "e", "t").sendOtp("12345", "482913", 10);

        assertThat(calls.get()).isZero();
    }

    @Test
    void normalises_a_stored_number_into_the_plus_form_twilio_wants() {
        sender("+15550001111", null, "e", "t").sendOtp("91 98765-43210", "1", 5);

        assertThat(lastForm.get().get("To")).isEqualTo("+919876543210");
    }

    @Test
    void reports_that_it_really_delivers() {
        // This is what the retailer verification gate reads. A sender that claimed to
        // deliver and did not would lock every retailer behind a code that never comes.
        assertThat(sender("+15550001111", null, "e", "t").deliversForReal()).isTrue();
    }

    private static Map<String, String> parseForm(String encoded) {
        Map<String, String> form = new LinkedHashMap<>();
        if (encoded.isBlank()) return form;
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                     URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }
}
