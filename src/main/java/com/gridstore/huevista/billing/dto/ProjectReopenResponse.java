package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The outcome of paying to reopen a lapsed project: which project, and until when.
 *
 * Deliberately not a full ProjectResponse. The studio reloads the project itself right
 * after this, and rebuilding the whole thing here — regions, presigned mask URLs, the
 * cleaned image — would duplicate that work on the one path where the user is watching a
 * payment spinner.
 */
@Data
@Builder
public class ProjectReopenResponse {

    private String projectId;

    /** When the freshly-extended window ends. Null while it is paused by a subscription. */
    private LocalDateTime accessExpiresAt;

    /** True while a live subscription is holding the window — the paid days are banked. */
    private boolean paused;

    /** What was charged, in paise. */
    private int amountPaise;

    /** Points spent instead, when the reopen was paid from the reward balance. */
    private int pointsSpent;

    /** Days added by this reopen. */
    private int daysAdded;

    /**
     * Unstarted projects still on the account after this reopen.
     *
     * <p>Sent on every rail, not only the one that spends a credit, so the view-only banner
     * can redraw its "use one of your projects" offer from the answer it already has. A
     * card reopen leaves the number alone; the banner still has to be told that, or it goes
     * back to the account to be told nothing changed.
     */
    private int creditsLeft;
}
