package com.gridstore.huevista.support.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.ai.ClaudeService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.support.dto.ConversationResponse;
import com.gridstore.huevista.support.dto.ConversationSummaryResponse;
import com.gridstore.huevista.support.model.*;
import com.gridstore.huevista.support.repository.ConversationRepository;
import com.gridstore.huevista.support.repository.SupportMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportService {

    private static final String ESCALATE = "[[ESCALATE]]";
    private static final List<String> HUMAN_WORDS =
            List.of("human", "agent", "representative", "real person", "talk to someone", "speak to someone");

    private final ConversationRepository conversationRepo;
    private final SupportMessageRepository messageRepo;
    private final UserRepository userRepository;
    private final ClaudeService claude;
    private final com.gridstore.huevista.support.channel.WhatsAppService whatsAppService;

    @Transactional
    public ConversationResponse start(String userId, String message, String subject) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Conversation c = conversationRepo.save(Conversation.builder()
                .user(user)
                .channel(SupportChannel.IN_APP)
                .status(ConversationStatus.OPEN)
                .subject(subject != null && !subject.isBlank() ? subject.trim() : deriveSubject(message))
                .build());
        addMessage(c, MessageSender.USER, message);
        respond(c, user, message);
        return toResponse(c);
    }

    @Transactional
    public ConversationResponse postMessage(String userId, String conversationId, String body) {
        Conversation c = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        addMessage(c, MessageSender.USER, body);
        // While a human is handling it, just record the message; otherwise let the AI reply.
        if (c.getStatus() != ConversationStatus.NEEDS_HUMAN) {
            c.setStatus(ConversationStatus.OPEN);
            respond(c, c.getUser(), body);
        }
        return toResponse(c);
    }

    @Transactional
    public ConversationResponse requestHuman(String userId, String conversationId) {
        Conversation c = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        escalate(c);
        return toResponse(c);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummaryResponse> listMine(String userId) {
        return conversationRepo.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(c -> ConversationSummaryResponse.from(c, lastMessageText(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(String userId, String conversationId) {
        Conversation c = conversationRepo.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        return toResponse(c);
    }

    // ── External channels (WhatsApp / voice) ─────────────────────────────────

    /**
     * Handle an inbound message from an external contact (no app account). Finds
     * or creates their conversation on the channel, runs the AI agent, and returns
     * the text to send back (or null if a human is handling it / nothing to say).
     */
    @Transactional
    public String handleInbound(SupportChannel channel, String contactChannelId, String contactName, String text) {
        Conversation c = conversationRepo
                .findFirstByChannelAndContactChannelIdAndStatusNotOrderByUpdatedAtDesc(
                        channel, contactChannelId, ConversationStatus.RESOLVED)
                .orElseGet(() -> conversationRepo.save(Conversation.builder()
                        .channel(channel)
                        .status(ConversationStatus.OPEN)
                        .contactChannelId(contactChannelId)
                        .contactName(contactName)
                        .subject(deriveSubject(text))
                        .build()));
        addMessage(c, MessageSender.USER, text);

        if (c.getStatus() == ConversationStatus.NEEDS_HUMAN) {
            return null; // a human is handling it; just record the inbound message
        }
        if (wantsHuman(text)) {
            escalate(c);
            return "Okay — I'm connecting you with a team member who'll reply here shortly.";
        }
        Optional<String> reply = claude.complete(systemPrompt(c.getContactName(), "CUSTOMER"), buildTurns(c.getId()), 600);
        if (reply.isEmpty()) {
            String fallback = "Thanks for your message. I can't answer that automatically right now — "
                    + "a team member will follow up shortly.";
            addMessage(c, MessageSender.AI, fallback);
            escalate(c);
            return fallback;
        }
        String t = reply.get();
        boolean needsHuman = t.contains(ESCALATE);
        if (needsHuman) t = t.replace(ESCALATE, "").trim();
        if (!t.isBlank()) addMessage(c, MessageSender.AI, t);
        if (needsHuman) escalate(c);
        return t.isBlank() ? null : t;
    }

    /** Record a finished voice call (e.g. an ElevenLabs post-call transcript). */
    @Transactional
    public void recordVoiceTranscript(String contactChannelId, String contactName, String transcript, boolean escalate) {
        Conversation c = conversationRepo.save(Conversation.builder()
                .channel(SupportChannel.VOICE)
                .status(escalate ? ConversationStatus.NEEDS_HUMAN : ConversationStatus.RESOLVED)
                .contactChannelId(contactChannelId)
                .contactName(contactName)
                .subject("Voice call")
                .build());
        addMessage(c, MessageSender.SYSTEM, "Voice call transcript:");
        addMessage(c, MessageSender.USER, transcript);
    }

    // ── Staff (ADMIN) ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ConversationSummaryResponse> inbox() {
        return conversationRepo.findByStatusOrderByUpdatedAtDesc(ConversationStatus.NEEDS_HUMAN).stream()
                .map(c -> ConversationSummaryResponse.from(c, lastMessageText(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ConversationResponse getAsStaff(String conversationId) {
        Conversation c = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        return toResponse(c);
    }

    @Transactional
    public ConversationResponse agentReply(String agentUserId, String conversationId, String body) {
        Conversation c = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        userRepository.findById(agentUserId).ifPresent(c::setAssignee);
        // A human replying takes (or keeps) the conversation: without NEEDS_HUMAN the
        // customer's next message would get an AI answer mid-human-thread, and a reply
        // to an already-resolved conversation would be invisible in the staff inbox.
        c.setStatus(ConversationStatus.NEEDS_HUMAN);
        addMessage(c, MessageSender.AGENT, body);
        // An external contact never opens the in-app inbox — the reply must travel back
        // over their channel or the agent is typing into the void. Best-effort: the
        // message is stored either way, and a failed dispatch is visible in the logs.
        if (c.getChannel() == SupportChannel.WHATSAPP && c.getContactChannelId() != null) {
            boolean delivered = whatsAppService.sendText(c.getContactChannelId(), body);
            if (!delivered) {
                log.warn("Agent reply stored but WhatsApp dispatch failed: conversation={}", conversationId);
            }
        }
        return toResponse(c);
    }

    /**
     * Auto-close chats that have gone quiet. Every OPEN or NEEDS_HUMAN conversation
     * whose last activity is older than {@code idleFor} is marked RESOLVED, with a
     * SYSTEM note so both ends see why it ended. This is what makes a chat "get
     * over": a customer coming back the next day starts a fresh thread instead of
     * reopening a stale one (the in-app widget and the WhatsApp lookup both resume
     * only non-resolved conversations). Returns how many were closed.
     */
    @Transactional
    public int autoCloseIdle(Duration idleFor) {
        LocalDateTime cutoff = LocalDateTime.now().minus(idleFor);
        List<Conversation> stale = conversationRepo.findByStatusInAndUpdatedAtBefore(
                List.of(ConversationStatus.OPEN, ConversationStatus.NEEDS_HUMAN), cutoff);
        for (Conversation c : stale) {
            c.setStatus(ConversationStatus.RESOLVED);
            conversationRepo.save(c);
            addMessage(c, MessageSender.SYSTEM,
                    "This chat was closed after a period of inactivity. Send a new message to start a fresh chat.");
        }
        if (!stale.isEmpty()) {
            log.info("Auto-closed {} idle support conversation(s) (idle > {})", stale.size(), idleFor);
        }
        return stale.size();
    }

    @Transactional
    public ConversationResponse resolve(String conversationId) {
        Conversation c = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        c.setStatus(ConversationStatus.RESOLVED);
        conversationRepo.save(c);
        addMessage(c, MessageSender.SYSTEM, "This conversation was marked resolved.");
        return toResponse(c);
    }

    // ── Internals ────────────────────────────────────────────────────────────

    /** Generate the AI reply, or hand off to a human if AI is unavailable / unsure. */
    private void respond(Conversation c, User user, String latestUserMessage) {
        if (wantsHuman(latestUserMessage)) {
            escalate(c);
            return;
        }
        List<ClaudeService.Turn> turns = buildTurns(c.getId());
        String role = user.getRole() != null ? user.getRole().name() : "USER";
        Optional<String> reply = claude.complete(systemPrompt(user.getName(), role), turns, 600);
        if (reply.isEmpty()) {
            // AI disabled (dev) or errored → graceful fallback + handoff.
            addMessage(c, MessageSender.AI,
                    "Thanks for reaching out. I can't answer that automatically right now, "
                            + "so I've passed this to a team member — they'll reply here shortly.");
            escalate(c);
            return;
        }
        String text = reply.get();
        boolean needsHuman = text.contains(ESCALATE);
        if (needsHuman) {
            text = text.replace(ESCALATE, "").trim();
        }
        if (!text.isBlank()) {
            addMessage(c, MessageSender.AI, text);
        }
        if (needsHuman) {
            escalate(c);
        }
    }

    private void escalate(Conversation c) {
        if (c.getStatus() != ConversationStatus.NEEDS_HUMAN) {
            c.setStatus(ConversationStatus.NEEDS_HUMAN);
            conversationRepo.save(c);
            addMessage(c, MessageSender.SYSTEM, "Connecting you with a team member — they'll reply here soon.");
        }
    }

    private String systemPrompt(String name, String role) {
        return """
                You are HueVista's customer-support assistant. HueVista is an AI paint-shade
                visualiser for the Indian paint retail trade: retailers (shops) onboard walk-in
                customers with access codes; customers upload a room photo and preview real
                catalogue paint colours; there is a shade catalogue, a colour finder, and an
                "atelier" visualiser. Be concise, warm, and practical. Answer in the user's
                language. If you cannot help, or the user clearly needs a human (billing
                disputes, account problems, anything you're unsure about), add the token
                %s on its own line at the end of your reply.
                The person you're helping is %s (role: %s).
                """.formatted(ESCALATE, name != null ? name : "the customer", role != null ? role : "USER");
    }

    /** Build alternating user/assistant turns from the stored messages (collapsing
     *  consecutive same-role messages and dropping system notices). */
    private List<ClaudeService.Turn> buildTurns(String conversationId) {
        List<ClaudeService.Turn> turns = new ArrayList<>();
        for (SupportMessage m : messageRepo.findByConversationIdOrderByCreatedAtAscIdAsc(conversationId)) {
            String role = switch (m.getSender()) {
                case USER -> "user";
                case AI, AGENT -> "assistant";
                case SYSTEM -> null;
            };
            if (role == null) continue;
            if (!turns.isEmpty() && turns.get(turns.size() - 1).role().equals(role)) {
                ClaudeService.Turn prev = turns.remove(turns.size() - 1);
                turns.add(new ClaudeService.Turn(role, prev.text() + "\n\n" + m.getBody()));
            } else {
                turns.add(new ClaudeService.Turn(role, m.getBody()));
            }
        }
        // Anthropic requires the first turn to be a user message.
        while (!turns.isEmpty() && !turns.get(0).role().equals("user")) {
            turns.remove(0);
        }
        return turns;
    }

    private boolean wantsHuman(String message) {
        if (message == null) return false;
        String m = message.toLowerCase();
        return HUMAN_WORDS.stream().anyMatch(m::contains);
    }

    private void addMessage(Conversation c, MessageSender sender, String body) {
        messageRepo.save(SupportMessage.builder().conversation(c).sender(sender).body(body).build());
        // Touch the conversation so updatedAt reflects the latest MESSAGE, not the last
        // status change. The staff inbox and the user's list sort by updatedAt, and the
        // WhatsApp inbound lookup picks "most recently updated" — without this a
        // NEEDS_HUMAN conversation receiving new user messages never moves up.
        c.setUpdatedAt(LocalDateTime.now());
        conversationRepo.save(c);
    }

    private String lastMessageText(String conversationId) {
        return messageRepo.findTopByConversationIdOrderByCreatedAtDescIdDesc(conversationId)
                .map(SupportMessage::getBody)
                .orElse("");
    }

    private ConversationResponse toResponse(Conversation c) {
        return ConversationResponse.from(c, messageRepo.findByConversationIdOrderByCreatedAtAscIdAsc(c.getId()));
    }

    private String deriveSubject(String message) {
        if (message == null || message.isBlank()) return "Support request";
        String s = message.strip().replaceAll("\\s+", " ");
        return s.length() <= 60 ? s : s.substring(0, 57) + "…";
    }
}
