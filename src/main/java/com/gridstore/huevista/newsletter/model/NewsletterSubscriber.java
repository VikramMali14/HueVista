package com.gridstore.huevista.newsletter.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Somebody who asked for the monthly letter.
 *
 * One row per address, ever — re-subscribing after an unsubscribe flips this row back
 * rather than opening a second one, so "is this address on the list?" has exactly one
 * answer and a resubscribe can never produce two copies of the same send.
 *
 * The address is the only personal datum here. It deliberately has no link to a
 * {@code User}: most people who sign up from the journal have no account, and the ones
 * who do may well want the letter at a different address than the one they log in with.
 */
@Entity
@Table(name = "newsletter_subscribers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewsletterSubscriber {

    public enum Status { SUBSCRIBED, UNSUBSCRIBED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Normalised (trimmed, lower-cased) — the unique key of the list. */
    @Column(nullable = false, unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.SUBSCRIBED;

    /** Which surface the signup came from ("journal", "footer", …) — for attribution only. */
    @Column(length = 60)
    private String source;

    /**
     * The secret in the unsubscribe link. Random per row and never reused, so leaving the
     * list needs no login and knowing an address is not enough to remove it — which is the
     * difference between an unsubscribe link and a way to unsubscribe other people.
     */
    @Column(nullable = false, unique = true, length = 64)
    private String unsubscribeToken;

    /** When the welcome mail went out — null means it still has to. */
    private LocalDateTime welcomedAt;

    private LocalDateTime unsubscribedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public boolean isSubscribed() {
        return status == Status.SUBSCRIBED;
    }
}
