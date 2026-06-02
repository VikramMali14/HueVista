package com.gridstore.huevista.support.dto;

import com.gridstore.huevista.support.model.MessageSender;
import com.gridstore.huevista.support.model.SupportMessage;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MessageResponse {
    private String id;
    private MessageSender sender;
    private String body;
    private LocalDateTime createdAt;

    public static MessageResponse from(SupportMessage m) {
        return MessageResponse.builder()
                .id(m.getId())
                .sender(m.getSender())
                .body(m.getBody())
                .createdAt(m.getCreatedAt())
                .build();
    }
}
