package com.gridstore.huevista.support.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageSender sender;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String body;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
