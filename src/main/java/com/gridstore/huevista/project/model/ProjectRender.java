package com.gridstore.huevista.project.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One photorealistic AI render of a finished project, in one of the combos the customer
 * actually took away on a colour board.
 *
 * The studio's own recolour is a mask-and-multiply over the cleaned photo: exact about
 * colour, honest about which surface is which, and obviously a preview. This is the other
 * thing — the room as a photograph, with the light, the furniture and the finish the
 * customer asked for. It costs a paid Nano Banana Pro call, so there is exactly one
 * included per project and every further one is bought.
 *
 * A render is always tied to a {@link ProjectPdfPage}. That is the constraint that keeps
 * it honest rather than a second, freer studio: the customer renders a combination they
 * already chose and were handed on paper, not a fresh one invented after the project
 * closed.
 *
 * The options are stored as enums, one column each, rather than as a JSON blob. They are
 * few, they are closed sets, and every one of them is read back to rebuild the prompt when
 * a render is retried — a shape a query can filter on is worth more here than one that
 * can absorb a field nobody planned.
 */
@Entity
@Table(name = "project_renders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectRender {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** The combo being rendered. Null only if the page was deleted under it. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id")
    private ProjectPdfPage page;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Status status = Status.QUEUED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TimeOfDay timeOfDay;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BorderMode borderMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Lighting lighting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Furnishing furnishing;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private RenderStyle style;

    /** Anything the customer typed. Clamped and framed as untrusted before it reaches
     *  the model — see ProjectRenderService. */
    @Column(length = 500)
    private String note;

    /**
     * Where the finished image lives — the storage KEY, never a presigned URL. A
     * presigned URL expires (an hour by default), so storing one freezes a dead link into
     * the row; every read path signs a fresh one instead.
     */
    @Column(length = 512)
    private String storageKey;

    /** Why it failed, when it did. Shown to the owner, so it says something usable. */
    @Column(length = 500)
    private String failureReason;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    public enum Status {
        /** Accepted, allowance spent, not yet picked up. */
        QUEUED,
        /** The image model is working. */
        RUNNING,
        /** Finished; {@link #storageKey} is set. */
        READY,
        /** Gave up; {@link #failureReason} says why, and the allowance was handed back. */
        FAILED
    }

    /** Daylight or after dark. Changes the whole prompt, not just the sky. */
    public enum TimeOfDay { DAY, NIGHT }

    /**
     * What happens to the trim and borders.
     *
     * KEEP_ORIGINAL sends the project's own region masks along with the photo, so the
     * model paints inside the boundaries the customer drew or approved. AI_SUGGESTED sends
     * no masks and asks the model to propose the banding and trim treatment itself — a
     * different product, and the reason the two are a choice rather than a setting.
     */
    public enum BorderMode { KEEP_ORIGINAL, AI_SUGGESTED }

    public enum Lighting { NATURAL, WARM, COOL, DRAMATIC }

    /** KEEP leaves the room as photographed; STAGED dresses it; EMPTY clears it out. */
    public enum Furnishing { KEEP, STAGED, EMPTY }

    public enum RenderStyle { MODERN, MINIMAL, TRADITIONAL, HERITAGE, LUXE }
}
