package com.gridstore.huevista.support.dto;

import com.gridstore.huevista.support.model.Conversation;
import com.gridstore.huevista.support.model.ConversationStatus;
import com.gridstore.huevista.support.model.SupportChannel;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Row in a list/inbox view — no message bodies. */
@Data
@Builder
public class ConversationSummaryResponse {
    private String id;
    private SupportChannel channel;
    private ConversationStatus status;
    private String subject;
    private String requesterName;
    private String requesterEmail;
    private String requesterRole;
    private String lastMessage;
    private LocalDateTime updatedAt;

    public static ConversationSummaryResponse from(Conversation c, String lastMessage) {
        var u = c.getUser();
        String name = u != null ? u.getName()
                : (c.getContactName() != null ? c.getContactName() : c.getContactChannelId());
        return ConversationSummaryResponse.builder()
                .id(c.getId())
                .channel(c.getChannel())
                .status(c.getStatus())
                .subject(c.getSubject())
                .requesterName(name)
                .requesterEmail(u != null ? u.getEmail() : null)
                .requesterRole(u != null && u.getRole() != null ? u.getRole().name() : c.getChannel().name())
                .lastMessage(lastMessage)
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
