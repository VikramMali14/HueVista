package com.gridstore.huevista.support.controller;

import com.gridstore.huevista.support.dto.*;
import com.gridstore.huevista.support.service.SupportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/support")
@RequiredArgsConstructor
@Tag(name = "Support", description = "AI support conversations with human handoff")
public class SupportController {

    private final SupportService supportService;

    // ── Requester (customer or retailer) ─────────────────────────────────────

    @Operation(summary = "Start a support conversation (AI replies; escalates to a human if needed)")
    @PostMapping("/conversations")
    public ResponseEntity<ConversationResponse> start(
            @Valid @RequestBody StartConversationRequest request, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(supportService.start(userId(auth), request.getMessage(), request.getSubject()));
    }

    @Operation(summary = "List my support conversations")
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationSummaryResponse>> mine(Authentication auth) {
        return ResponseEntity.ok(supportService.listMine(userId(auth)));
    }

    @Operation(summary = "Get one of my conversations with its messages")
    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationResponse> get(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(supportService.get(userId(auth), id));
    }

    @Operation(summary = "Post a message to my conversation (AI replies unless a human has it)")
    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<ConversationResponse> post(
            @PathVariable String id, @Valid @RequestBody PostMessageRequest request, Authentication auth) {
        return ResponseEntity.ok(supportService.postMessage(userId(auth), id, request.getBody()));
    }

    @Operation(summary = "Ask for a human agent")
    @PostMapping("/conversations/{id}/request-human")
    public ResponseEntity<ConversationResponse> requestHuman(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(supportService.requestHuman(userId(auth), id));
    }

    // ── Staff (ADMIN) ────────────────────────────────────────────────────────

    @Operation(summary = "Inbox of conversations awaiting a human")
    @GetMapping("/inbox")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ConversationSummaryResponse>> inbox() {
        return ResponseEntity.ok(supportService.inbox());
    }

    @Operation(summary = "Read any conversation (staff)")
    @GetMapping("/inbox/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConversationResponse> staffGet(@PathVariable String id) {
        return ResponseEntity.ok(supportService.getAsStaff(id));
    }

    @Operation(summary = "Reply as a human agent")
    @PostMapping("/inbox/{id}/reply")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConversationResponse> agentReply(
            @PathVariable String id, @Valid @RequestBody AgentReplyRequest request, Authentication auth) {
        return ResponseEntity.ok(supportService.agentReply(userId(auth), id, request.getBody()));
    }

    @Operation(summary = "Mark a conversation resolved")
    @PostMapping("/inbox/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConversationResponse> resolve(@PathVariable String id) {
        return ResponseEntity.ok(supportService.resolve(id));
    }

    private String userId(Authentication auth) {
        return auth.getName();
    }
}
