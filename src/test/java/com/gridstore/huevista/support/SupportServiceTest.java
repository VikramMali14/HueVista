package com.gridstore.huevista.support;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.ai.ClaudeService;
import com.gridstore.huevista.support.channel.WhatsAppService;
import com.gridstore.huevista.support.model.Conversation;
import com.gridstore.huevista.support.model.ConversationStatus;
import com.gridstore.huevista.support.model.MessageSender;
import com.gridstore.huevista.support.model.SupportChannel;
import com.gridstore.huevista.support.model.SupportMessage;
import com.gridstore.huevista.support.repository.ConversationRepository;
import com.gridstore.huevista.support.repository.SupportMessageRepository;
import com.gridstore.huevista.support.service.SupportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The "both ends" chat fixes: every new message touches the conversation's updatedAt
 * (inbox ordering / relative times), an agent reply re-takes the conversation for a
 * human, and agent replies to WhatsApp contacts are actually dispatched outbound.
 */
class SupportServiceTest {

    private final ConversationRepository convos = mock(ConversationRepository.class);
    private final SupportMessageRepository messages = mock(SupportMessageRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ClaudeService claude = mock(ClaudeService.class);
    private final WhatsAppService whatsApp = mock(WhatsAppService.class);

    private final SupportService service =
            new SupportService(convos, messages, users, claude, whatsApp);

    private static Conversation conversation(SupportChannel channel, ConversationStatus status) {
        User user = new User();
        user.setId("user-1");
        user.setName("Asha");
        Conversation c = Conversation.builder()
                .id("conv-1")
                .user(user)
                .channel(channel)
                .status(status)
                .subject("Help")
                .build();
        c.setUpdatedAt(LocalDateTime.now().minusDays(1));
        return c;
    }

    private void stubMessageReads() {
        when(messages.findByConversationIdOrderByCreatedAtAscIdAsc("conv-1")).thenReturn(List.of());
    }

    @Test
    void posting_a_message_touches_the_conversation_updatedAt() {
        Conversation c = conversation(SupportChannel.IN_APP, ConversationStatus.NEEDS_HUMAN);
        LocalDateTime before = c.getUpdatedAt();
        when(convos.findByIdAndUserId("conv-1", "user-1")).thenReturn(Optional.of(c));
        stubMessageReads();

        service.postMessage("user-1", "conv-1", "hello again");

        // NEEDS_HUMAN: no AI reply, but the row must still be saved with a fresh
        // updatedAt so the staff inbox re-sorts and "waiting since" stays honest.
        verify(convos, atLeastOnce()).save(c);
        assertThat(c.getUpdatedAt()).isAfter(before);
        ArgumentCaptor<SupportMessage> saved = ArgumentCaptor.forClass(SupportMessage.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getSender()).isEqualTo(MessageSender.USER);
    }

    @Test
    void agent_reply_reopens_a_resolved_conversation_for_a_human() {
        Conversation c = conversation(SupportChannel.IN_APP, ConversationStatus.RESOLVED);
        when(convos.findById("conv-1")).thenReturn(Optional.of(c));
        when(users.findById("agent-1")).thenReturn(Optional.empty());
        stubMessageReads();

        service.agentReply("agent-1", "conv-1", "We refunded you.");

        assertThat(c.getStatus()).isEqualTo(ConversationStatus.NEEDS_HUMAN);
    }

    @Test
    void agent_reply_to_a_whatsapp_contact_is_dispatched_outbound() {
        Conversation c = conversation(SupportChannel.WHATSAPP, ConversationStatus.NEEDS_HUMAN);
        c.setContactChannelId("919999888877");
        when(convos.findById("conv-1")).thenReturn(Optional.of(c));
        when(users.findById("agent-1")).thenReturn(Optional.empty());
        when(whatsApp.sendText(anyString(), anyString())).thenReturn(true);
        stubMessageReads();

        service.agentReply("agent-1", "conv-1", "Your code is on its way.");

        verify(whatsApp).sendText("919999888877", "Your code is on its way.");
    }

    @Test
    void agent_reply_in_app_never_touches_whatsapp() {
        Conversation c = conversation(SupportChannel.IN_APP, ConversationStatus.NEEDS_HUMAN);
        when(convos.findById("conv-1")).thenReturn(Optional.of(c));
        when(users.findById("agent-1")).thenReturn(Optional.empty());
        stubMessageReads();

        service.agentReply("agent-1", "conv-1", "Done.");

        verify(whatsApp, org.mockito.Mockito.never()).sendText(anyString(), anyString());
    }

    @Test
    void user_message_while_human_has_it_does_not_trigger_the_ai() {
        Conversation c = conversation(SupportChannel.IN_APP, ConversationStatus.NEEDS_HUMAN);
        when(convos.findByIdAndUserId("conv-1", "user-1")).thenReturn(Optional.of(c));
        stubMessageReads();

        service.postMessage("user-1", "conv-1", "any update?");

        verify(claude, org.mockito.Mockito.never()).complete(anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(c.getStatus()).isEqualTo(ConversationStatus.NEEDS_HUMAN);
    }
}
