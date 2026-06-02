package com.gridstore.huevista.support.channel;

import com.gridstore.huevista.support.model.SupportChannel;
import com.gridstore.huevista.support.service.SupportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Meta WhatsApp Cloud API webhook. PUBLIC (Meta calls it) — see SecurityConfig
 * permitAll for /api/support/webhooks/**. The backend must be publicly reachable
 * (a tunnel like ngrok in dev, or the deployed URL) for Meta to deliver here.
 */
@Slf4j
@RestController
@RequestMapping("/api/support/webhooks/whatsapp")
@RequiredArgsConstructor
public class WhatsAppController {

    private final WhatsAppService whatsApp;
    private final SupportService supportService;

    /** Webhook verification handshake (Meta calls this once when you subscribe). */
    @GetMapping
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && whatsApp.verifyTokenMatches(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).body("Forbidden");
    }

    /** Inbound messages. Always 200 quickly so Meta doesn't retry. */
    @PostMapping
    @SuppressWarnings("unchecked")
    public ResponseEntity<Void> receive(@RequestBody Map<String, Object> payload) {
        try {
            List<Map<String, Object>> entry = (List<Map<String, Object>>) payload.get("entry");
            if (entry == null) return ResponseEntity.ok().build();
            for (Map<String, Object> e : entry) {
                List<Map<String, Object>> changes = (List<Map<String, Object>>) e.get("changes");
                if (changes == null) continue;
                for (Map<String, Object> ch : changes) {
                    Map<String, Object> value = (Map<String, Object>) ch.get("value");
                    if (value == null) continue;
                    String contactName = extractContactName(value);
                    List<Map<String, Object>> messages = (List<Map<String, Object>>) value.get("messages");
                    if (messages == null) continue;
                    for (Map<String, Object> m : messages) {
                        String from = (String) m.get("from");
                        String text = extractText(m);
                        if (from == null || text == null) continue;
                        String reply = supportService.handleInbound(SupportChannel.WHATSAPP, from, contactName, text);
                        if (reply != null) whatsApp.sendText(from, reply);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("WhatsApp inbound parse failed: {}", ex.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private String extractText(Map<String, Object> message) {
        Object t = message.get("text");
        if (t instanceof Map<?, ?> map) {
            Object body = ((Map<String, Object>) map).get("body");
            return body != null ? body.toString() : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractContactName(Map<String, Object> value) {
        try {
            List<Map<String, Object>> contacts = (List<Map<String, Object>>) value.get("contacts");
            if (contacts != null && !contacts.isEmpty()) {
                Map<String, Object> profile = (Map<String, Object>) contacts.get(0).get("profile");
                if (profile != null) return (String) profile.get("name");
            }
        } catch (Exception ignored) { /* best-effort */ }
        return null;
    }
}
