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

    /**
     * How good an image was asked for — which model made it, and what it cost.
     *
     * <p>Stored rather than derived from {@link #creditsSpent}, because the price is
     * configuration and a tier renamed or re-priced next year must not change what an image
     * made today says it was. It is also the only record of WHICH model family produced a
     * given picture, which is the first question asked when one comes back wrong.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Quality quality = Quality.PREMIUM;

    /**
     * Which photograph of the room the model was given to work from.
     *
     * <p>Stored rather than assumed, for the same reason {@link #quality} is: it is the
     * first thing worth knowing when two images of the same room come back different, and
     * "whichever one the code preferred in August" is not an answer. Renders written before
     * the choice existed read CLEANED, which is what they were given.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private SourceImage sourceImage = SourceImage.CLEANED;

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

    /**
     * Which pocket paid for this render: the project's own allowance, or an AI credit out
     * of the owner's wallet.
     *
     * <p>Recorded because the refund has to go back where the charge came from. A project a
     * shop gave a customer carries NO included render, so every image on it is a spent
     * credit — handing back a project allowance there would invent an image out of nothing,
     * and decrementing {@code rendersUsed} below zero would let the next one run free. The
     * reverse mistake is worse: silently keeping a customer's ₹99 credit for a picture the
     * model refused to make.
     *
     * <p>False on every render written before credits existed, which is correct — those
     * were all paid out of the project allowance.
     */
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    @Builder.Default
    private boolean paidWithCredit = false;

    /**
     * Whose wallet paid, when {@link #paidWithCredit}. Null otherwise.
     *
     * Stored rather than re-derived from the project at refund time because the refund runs
     * on the worker thread minutes later, and a project re-pointed at a new account in
     * between (which happens the moment a guest signs up) would send the credit to the
     * wrong wallet — or to none at all.
     */
    @Column(length = 64)
    private String paidByUserId;

    /** How many credits were taken, when {@link #paidWithCredit}. Stored rather than read
     *  from configuration at refund time, so a price change cannot alter what an already
     *  charged render owes back. */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int creditsSpent = 0;

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

    /**
     * Which photograph of the room to paint: the cleaned one, or the one that was taken.
     *
     * <p>CLEANED is the better starting point and stays the default. It has the clutter
     * removed and every paintable surface flattened to white, so the model tints a neutral
     * wall instead of arguing with the colour already on it — which is why it was the only
     * option for as long as this was a decision the code made silently.
     *
     * <p>ORIGINAL exists because the clean-up is itself an AI step and sometimes takes
     * something real with it: a picture rail, a texture, a shadow that was the point of the
     * photograph. Somebody who can see both pictures knows which of them is their room
     * better than the pipeline does, and the credit is theirs to spend either way.
     *
     * <p>ORIGINAL is also what a room with no cleaned photo silently fell back to. That
     * fallback is unchanged — asking for CLEANED on a room that has none still gets the
     * original — but it is now the answer to a question rather than a hidden substitution.
     */
    public enum SourceImage { CLEANED, ORIGINAL }

    /** KEEP leaves the room as photographed; STAGED dresses it; EMPTY clears it out. */
    public enum Furnishing { KEEP, STAGED, EMPTY }

    public enum RenderStyle { MODERN, MINIMAL, TRADITIONAL, HERITAGE, LUXE }

    /**
     * How good an image to make: a different model, at a different size, for a different
     * number of credits.
     *
     * <p>Two tiers rather than one, because "one photorealistic image" was never one
     * thing. The models behind them differ by an order of magnitude in what they cost us to
     * run, and flattening that into a single price meant either overcharging everybody who
     * wanted a quick look or losing money on everybody who wanted the good one.
     *
     * <p>Two rather than THREE, because the third was not a choice anybody made. The old
     * top tier cost FOUR credits — twice the tier below it, for a difference most people
     * could not see on a phone — and a price list with a line nobody picks is a price list
     * that makes the other two harder to read. It is retired, and the dearest thing on sale
     * is now two credits.
     *
     * <p>The ordering matters and is relied on: PREMIUM is the floor, and every project's
     * INCLUDED image is a PREMIUM one. Choosing better on a room that still has its included
     * image spends the allowance and tops up the difference in credits, rather than
     * refusing — see {@code ProjectRenderService#charge}.
     *
     * <p>What each tier runs on is configuration, not code
     * ({@code replicate.render.quality.*}), so a better model can be promoted into a tier
     * without a migration. What is fixed here is the SHAPE: a primary that is asked first,
     * and a fallback for when it is out of capacity.
     */
    public enum Quality {
        /** One credit. A clear, honest photograph of the room in its new colours. */
        PREMIUM,
        /** Two credits. A better model at a bigger size: sharper, truer to the building's
         *  own lines, and the one to make when the picture is going to be printed or shown
         *  to somebody. */
        LUXURY
    }
}
