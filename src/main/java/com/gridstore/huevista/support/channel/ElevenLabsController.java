package com.gridstore.huevista.support.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.support.service.SupportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * ElevenLabs Conversational AI (voice) integration. The voice agent itself runs
 * in ElevenLabs (STT + LLM + TTS, connected to a phone number via Twilio/SIP);
 * these are the two backend hooks it calls. PUBLIC (ElevenLabs calls them) — see
 * SecurityConfig permitAll for /api/support/webhooks/**. The backend must be
 * publicly reachable. UNTESTED until an ElevenLabs account + number are set up.
 *
 * Configure in the ElevenLabs agent dashboard:
 *  - a "server tool" pointing at POST .../elevenlabs/tool (for live escalation / lookups)
 *  - the post-call webhook pointing at POST .../elevenlabs/post-call (transcript)
 * Add HMAC signature verification with your elevenlabs.webhook-secret before prod.
 */
@Slf4j
@RestController
@RequestMapping("/api/support/webhooks/elevenlabs")
@RequiredArgsConstructor
public class ElevenLabsController {

    private final SupportService supportService;
    private final ObjectMapper objectMapper;

    @Value("${elevenlabs.webhook-secret:}")
    private String webhookSecret;

    /** A server tool the voice agent can invoke mid-call (e.g. escalate to a human). */
    @PostMapping("/tool")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> tool(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "ElevenLabs-Signature", required = false) String signature) {
        if (rawBody == null || !verified(rawBody, signature)) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Invalid signature."));
        }
        Map<String, Object> body;
        try {
            body = objectMapper.readValue(rawBody, Map.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "Bad payload."));
        }
        String action = str(body.get("action"));
        String caller = firstNonBlank(str(body.get("caller")), str(body.get("phone")), "voice-caller");
        String summary = firstNonBlank(str(body.get("summary")), str(body.get("reason")), "Caller requested assistance.");
        if ("escalate".equalsIgnoreCase(action) || action == null) {
            supportService.recordVoiceTranscript(caller, str(body.get("name")), summary, true);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "message", "A team member has been notified and will follow up."));
        }
        return ResponseEntity.ok(Map.of("status", "ok", "message", "Noted."));
    }

    /** Post-call webhook: log the finished call's transcript as a VOICE conversation. */
    @PostMapping("/post-call")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> postCall(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = "ElevenLabs-Signature", required = false) String signature) {
        if (rawBody == null || !verified(rawBody, signature)) {
            log.warn("ElevenLabs post-call webhook rejected: bad/missing signature");
            return ResponseEntity.status(401).build();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(rawBody, Map.class);
            Map<String, Object> data = (Map<String, Object>) payload.getOrDefault("data", payload);
            String caller = firstNonBlank(deepStr(data, "metadata", "phone_number"),
                    str(data.get("conversation_id")), "voice-caller");
            String transcript = buildTranscript(data.get("transcript"));
            boolean escalate = needsFollowUp(data.get("analysis"));
            supportService.recordVoiceTranscript(caller, null,
                    transcript.isBlank() ? "(no transcript provided)" : transcript, escalate);
        } catch (Exception e) {
            log.warn("ElevenLabs post-call parse failed: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private String buildTranscript(Object transcript) {
        if (!(transcript instanceof List<?> turns)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object t : turns) {
            if (t instanceof Map<?, ?> m) {
                Map<String, Object> turn = (Map<String, Object>) m;
                String role = firstNonBlank(str(turn.get("role")), str(turn.get("speaker")), "");
                String msg = firstNonBlank(str(turn.get("message")), str(turn.get("text")), "");
                if (!msg.isBlank()) sb.append(role.isBlank() ? "" : role + ": ").append(msg).append("\n");
            }
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private boolean needsFollowUp(Object analysis) {
        if (analysis instanceof Map<?, ?> m) {
            Object flag = ((Map<String, Object>) m).get("escalate");
            return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(str(flag));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private String deepStr(Map<String, Object> map, String a, String b) {
        Object inner = map.get(a);
        if (inner instanceof Map<?, ?> m) return str(((Map<String, Object>) m).get(b));
        return null;
    }

    /**
     * Verify the ElevenLabs HMAC signature header ("t=<ts>,v0=<hex>") over
     * "{timestamp}.{body}". If no webhook secret is configured we don't block
     * (the channel is inert until set up); once set, a bad signature is rejected.
     */
    private boolean verified(byte[] body, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) return true;
        if (signatureHeader == null) return false;
        String t = null, v0 = null;
        for (String part : signatureHeader.split(",")) {
            String p = part.trim();
            if (p.startsWith("t=")) t = p.substring(2);
            else if (p.startsWith("v0=")) v0 = p.substring(3);
        }
        if (t == null || v0 == null) return false;
        String signedPayload = t + "." + new String(body, StandardCharsets.UTF_8);
        String expected = WebhookSignatures.hmacSha256Hex(webhookSecret, signedPayload.getBytes(StandardCharsets.UTF_8));
        return WebhookSignatures.constantTimeEquals(expected, v0);
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
