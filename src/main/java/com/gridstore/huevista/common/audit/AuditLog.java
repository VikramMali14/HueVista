package com.gridstore.huevista.common.audit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** Immutable record of a sensitive action (role change, cancellation, deletion). */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actorUserId"),
        @Index(name = "idx_audit_action", columnList = "action")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Who performed the action (user id), or null for system actions. */
    private String actorUserId;

    @Column(nullable = false)
    private String action;       // e.g. ROLE_CHANGE, SUBSCRIPTION_CANCEL, PROJECT_DELETE

    private String targetType;   // e.g. USER, SUBSCRIPTION, PROJECT
    private String targetId;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
