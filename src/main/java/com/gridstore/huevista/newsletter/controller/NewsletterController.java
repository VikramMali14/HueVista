package com.gridstore.huevista.newsletter.controller;

import com.gridstore.huevista.newsletter.dto.NewsletterSubscribeRequest;
import com.gridstore.huevista.newsletter.service.NewsletterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The monthly letter's public sign-up and unsubscribe.
 *
 * Both answers are deliberately the same whatever the list already contains: an address
 * that is new, one that is already on, and one coming back all get "check your inbox", so
 * this endpoint cannot be used to test whether somebody subscribed.
 */
@RestController
@RequestMapping("/api/newsletter")
@RequiredArgsConstructor
@Validated
@Tag(name = "Newsletter", description = "The monthly letter: join and leave")
public class NewsletterController {

    private final NewsletterService newsletterService;

    @Operation(summary = "Join the monthly letter",
            description = "Adds the address to the list and sends the welcome mail. Idempotent — "
                    + "the same address twice is one subscription and one welcome. The response "
                    + "never reveals whether the address was already subscribed.")
    @PostMapping("/subscribe")
    public ResponseEntity<Map<String, String>> subscribe(
            @Valid @RequestBody NewsletterSubscribeRequest request) {
        newsletterService.subscribe(request.getEmail(), request.getSource());
        return ResponseEntity.ok(Map.of(
                "status", "SUBSCRIBED",
                "message", "You're on the list — check your inbox for the welcome note."));
    }

    @Operation(summary = "Leave the monthly letter",
            description = "Removes the address the token belongs to. No account needed — the "
                    + "token is the authorisation, and it only ever removes its own address.")
    @PostMapping("/unsubscribe")
    public ResponseEntity<Map<String, String>> unsubscribe(
            @RequestParam @NotBlank String token) {
        newsletterService.unsubscribe(token);
        return ResponseEntity.ok(Map.of(
                "status", "UNSUBSCRIBED",
                "message", "You're off the list. No more letters — thank you for reading."));
    }
}
