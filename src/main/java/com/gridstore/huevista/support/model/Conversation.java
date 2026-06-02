package com.gridstore.huevista.support.model;

import com.gridstore.huevista.auth.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "support_conversations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Who opened it (customer or retailer). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SupportChannel channel = SupportChannel.IN_APP;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ConversationStatus status = ConversationStatus.OPEN;

    private String subject;

    /** The human agent who picked it up after escalation (nullable). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    private User assignee;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
