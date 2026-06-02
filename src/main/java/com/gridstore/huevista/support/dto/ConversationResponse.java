package com.gridstore.huevista.support.dto;

import com.gridstore.huevista.support.model.Conversation;
import com.gridstore.huevista.support.model.ConversationStatus;
import com.gridstore.huevista.support.model.SupportChannel;
import com.gridstore.huevista.support.model.SupportMessage;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ConversationResponse {
    private String id;
    private SupportChannel channel;
    private ConversationStatus status;
    private String subject;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<MessageResponse> messages;

    public static ConversationResponse from(Conversation c, List<SupportMessage> messages) {
        return ConversationResponse.builder()
                .id(c.getId())
                .channel(c.getChannel())
                .status(c.getStatus())
                .subject(c.getSubject())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .messages(messages.stream().map(MessageResponse::from).toList())
                .build();
    }
}
