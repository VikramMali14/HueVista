package com.gridstore.huevista.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The MSG91 client, driven against a stand-in for MSG91.
 *
 * <p>What is worth pinning is the request SHAPE. A wrong variable name or a number in
 * the wrong format is ACCEPTED by MSG91 and then dropped by the operator, so that
 * failure arrives with no error attached — the handset simply never rings. These tests
 * are the only place that shape is checked at all.
 *
 * <p>And the rule that a send never throws: a notification that could not go out must
 * not surface as a 500 on the signup that triggered it.
 */
class Msg91SmsSenderTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthKey = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Integer> statuses = new ArrayList<>();
    private final AtomicReference<String> body =
            new AtomicReference<>("{\"message\":\"queued\",\"type\":\"success\"}");
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v5/flow", exchange -> {
            calls.incrementAndGet();
            lastAuthKey.set(exchange.getRequestHeaders().getFirst("authkey"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = body.get().getBytes(StandardCharsets.UTF_8);
            int status = statuses.isEmpty() ? 200 : statuses.remove(0);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream o = exchange.getResponseBody()) { o.write(out); }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v5/flow";
    }

    private Msg91SmsSender sender(String otpVar, String validityVar, String senderId) {
        return new Msg91SmsSender("test-auth-key", "tmpl-123", otpVar, validityVar, senderId, url());
    }

    @Test
    void sends_the_template_id_the_variables_and_the_number_msg91_expects() throws Exception {
        sender("otp", "mins", null).sendOtp("+919876543210", "482913", 10);

        assertThat(lastAuthKey.get()).isEqualTo("test-auth-key");
        JsonNode sent = mapper.readTree(lastBody.get());
        assertThat(sent.path("template_id").asText()).isEqualTo("tmpl-123");

        JsonNode recipient = sent.path("recipients").get(0);
        // Country code and digits, NO plus — MSG91 misroutes anything else.
        assertThat(recipient.path("mobiles").asText()).isEqualTo("919876543210");
        // Named for the DLT template's own variables. This is the thing that silently
        // breaks delivery when it is wrong.
        assertThat(recipient.path("otp").asText()).isEqualTo("482913");
        assertThat(recipient.path("mins").asText()).isEqualTo("10");
    }

    @Test
    void uses_the_configured_variable_names() throws Exception {
        // Every DLT template names its own variables. Hardcoding ours would mean every
        // deployment had to get its template approved with our spelling.
        sender("var1", "var2", null).sendOtp("+919876543210", "111222", 5);

        JsonNode recipient = mapper.readTree(lastBody.get()).path("recipients").get(0);
        assertThat(recipient.path("var1").asText()).isEqualTo("111222");
        assertThat(recipient.path("var2").asText()).isEqualTo("5");
    }

    @Test
    void includes_a_sender_id_only_when_one_is_configured() throws Exception {
        sender("otp", "mins", null).sendOtp("+919876543210", "1", 5);
        assertThat(mapper.readTree(lastBody.get()).has("sender")).isFalse();

        sender("otp", "mins", "HUEVIS").sendOtp("+919876543210", "1", 5);
        assertThat(mapper.readTree(lastBody.get()).path("sender").asText()).isEqualTo("HUEVIS");
    }

    @Test
    void survives_an_error_body_returned_with_http_200() {
        // MSG91's characteristic failure: HTTP 200 with "type":"error" in the body, for a
        // rejected template or an exhausted balance. Reading only the status line would
        // report every one of those as a successful send.
        body.set("{\"message\":\"template not approved\",\"type\":\"error\"}");

        assertThatCode(() -> sender("otp", "mins", null).sendOtp("+919876543210", "482913", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void survives_a_rejected_auth_key() {
        statuses.add(401);
        body.set("{\"message\":\"Invalid authkey\",\"type\":\"error\"}");

        assertThatCode(() -> sender("otp", "mins", null).sendOtp("+919876543210", "482913", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void survives_msg91_being_unreachable() {
        server.stop(0);

        assertThatCode(() -> sender("otp", "mins", null).sendOtp("+919876543210", "482913", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void refuses_a_number_with_no_country_code_rather_than_guessing_one() {
        // Sent as-is it would be misrouted and silently lost. Guessing +91 would be
        // worse: it would occasionally reach a real stranger, and we would pay for it.
        sender("otp", "mins", null).sendOtp("12345", "482913", 10);

        assertThat(calls.get()).isZero();
    }

    @Test
    void strips_separators_a_stored_number_might_carry() throws Exception {
        sender("otp", "mins", null).sendOtp("+91 98765-43210", "1", 5);

        assertThat(mapper.readTree(lastBody.get()).path("recipients").get(0)
                .path("mobiles").asText()).isEqualTo("919876543210");
    }

    @Test
    void the_two_senders_are_honest_about_whether_they_deliver() {
        // This is what the retailer verification gate reads. A sender that claimed to
        // deliver and did not would lock every retailer behind a code that never comes.
        assertThat(sender("otp", "mins", null).deliversForReal()).isTrue();
        assertThat(new LoggingSmsSender().deliversForReal()).isFalse();
    }

    @Test
    void production_targets_msg91_itself() {
        // The five-argument constructor is the one the application uses; nothing should
        // be able to point a live deployment at another host.
        assertThat(Msg91SmsSender.FLOW_URL).isEqualTo("https://control.msg91.com/api/v5/flow");
    }
}
