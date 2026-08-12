package com.gridstore.huevista.project.model;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.image.model.UploadedImage;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    // Owner is EITHER a registered user OR — for an anonymous guest who redeemed a
    // shop access code — the access code (user stays null). Exactly one is set.
    // On guest sign-up the project is re-pointed to the new user (the shop keeps
    // visibility through the accessCode link, which is never cleared).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "access_code_id")
    private CustomerAccessCode accessCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_id", nullable = false)
    private UploadedImage image;

    @Column(nullable = false)
    private String name;

    // Optional context captured on the "new project" details step (nullable).
    private String roomType;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.CREATED;

    // Replicate prediction ID — used to poll SAM 2 job status
    private String replicatePredictionId;

    // Storage key (NOT full URL) of the cleaned image produced by
    // ImageCleanerService when the cleaner is enabled. The frontend
    // resolves a presigned URL via StorageService at read time. Null
    // when cleaning is disabled or hasn't run yet. Both the painted
    // preview canvas AND the mask images are aligned to THIS image
    // when present, falling back to the original UploadedImage when not.
    private String cleanedImageStorageKey;

    // Pixel size of THAT cleaned image. Not a cache of the original's dimensions:
    // the clean is a generative edit and then a local upscale, so it is a different
    // size (and occasionally a slightly different aspect) from the photo it came
    // from. Click-to-segment needs these — it converts a normalised click on the
    // canvas the user is looking at into pixel coordinates in the image it sends to
    // SAM, and doing that against the ORIGINAL's dimensions puts the point somewhere
    // else. Null when there is no cleaned image.
    private Integer cleanedImageWidth;

    private Integer cleanedImageHeight;

    // Storage key of the model's raw colour-coded mask (RED/GREEN/BLUE/BLACK
    // image) from the generation that was accepted. Kept purely for
    // diagnostics — the admin mask viewer compares it against the processed
    // per-region masks. Null for projects segmented before this shipped and
    // for projects whose regions are manual-only.
    private String rawMaskStorageKey;

    // ADMIN testing knob, set per segmentation request: TRUE = skip the
    // image-cleaner step for the next run (masks are generated straight from
    // the original photo). Null/FALSE = default behaviour (the cleaner runs
    // when globally enabled). Persisted on the project rather than carried in
    // the queue payload so the choice survives worker restarts and requeues.
    private Boolean skipImageClean;

    // How walls are created after the compulsory AI photo clean-up: "AUTO"
    // (null = default) runs AI wall detection and consumes one auto-mask
    // credit; "MANUAL" stops the pipeline after the clean-up so the user marks
    // walls themselves (free, unlimited). Persisted on the project rather than
    // carried in the queue payload so the choice survives worker restarts and
    // requeues — same reasoning as skipImageClean above.
    @Column(length = 16)
    private String maskMode;

    // When status == FAILED, why. Surfaced to the frontend so we can show the
    // user something actionable ("auto-segmentation not configured — click each
    // wall") instead of a generic failure. Stored as TEXT because Hibernate
    // exception messages with SQL fragments and presigned URLs blow past any
    // reasonable VARCHAR limit.
    @Column(columnDefinition = "TEXT")
    private String failureReason;

    // Which half of the run failed, for the code rather than the reader — the studio
    // turns a failure into a "report this" prompt and needs to tick the right box on
    // the user's behalf. See FailureStage. Null unless status == FAILED, and null on
    // failures that belong to neither stage.
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FailureStage failureStage;

    // Share link — null until generated
    @Column(unique = true)
    private String shareToken;

    private LocalDateTime shareExpiresAt;

    // Paint companies the sharing retailer opened up for the share-link viewer's
    // repaint palette, stored as a comma-separated list of brand names. Null or
    // blank means no restriction — the viewer may repaint with every brand.
    @Column(columnDefinition = "TEXT")
    private String shareBrands;

    /** Allowed share-repaint brand names as a list. Empty list = no restriction. */
    public List<String> getShareBrandList() {
        if (shareBrands == null || shareBrands.isBlank()) return List.of();
        return java.util.Arrays.stream(shareBrands.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public void setShareBrandList(List<String> brands) {
        if (brands == null || brands.isEmpty()) {
            this.shareBrands = null;
            return;
        }
        this.shareBrands = brands.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.joining(","));
    }

    // When the customer explicitly sent this project to the issuing shop
    // ("I'm done — this is the one"). Null until they do; the portal shows a
    // "sent by customer" badge and the shop owner gets a heads-up email.
    private LocalDateTime sentToShopAt;

    // ─── Paid access window ──────────────────────────────────────────────────
    // A project bought outright (rather than covered by a plan or a shop's access
    // code) carries its own validity. While the window is open the project is fully
    // workable; once it lapses the project drops to view-only — the last applied
    // colours stay readable, but nothing can be recoloured until it is reopened.
    //
    // The window PAUSES while the owner holds an active subscription, because a
    // subscriber shouldn't burn paid days they don't need: accessExpiresAt is cleared
    // and the leftover parked in accessRemainingSeconds. When the subscription ends it
    // resumes from exactly where it stopped. Parking the REMAINDER rather than freezing
    // an end date is what makes reconciliation idempotent — running it twice in the
    // same subscription state changes nothing.
    //
    // Null on all three means "no window": plan-covered and access-code projects are
    // governed by the plan / the code, not by this.
    private LocalDateTime accessExpiresAt;

    private LocalDateTime accessPausedAt;

    private Long accessRemainingSeconds;

    /** When this project was paid for outright, and what it cost. Null when it wasn't. */
    private LocalDateTime purchasedAt;

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int purchasePoints = 0;

    // ─── Closure ─────────────────────────────────────────────────────────────
    // When the job FINISHED. Deliberately a timestamp beside the access window rather
    // than a value on ProjectStatus, which tracks where the segmentation pipeline got
    // to (CREATED → SEGMENTING → SEGMENTED) and would lose that meaning if a lifecycle
    // state were mixed into it. The same reason sentToShopAt is a timestamp.
    //
    // Closure is NOT expiry. A lapsed window is a clock running out on unfinished work,
    // and reopening it costs ₹9. Closing is the customer saying they are done: they have
    // taken their colour boards and the AI render off it, and what stays visible
    // afterwards is only the shades that went into those boards. Reopening THAT unlocks
    // the whole catalogue again on a job that already delivered, so it costs ₹99.
    //
    // It also outranks every other kind of access. A closed project is read-only for its
    // owner whether or not a plan or a shop's code is covering them — see
    // ProjectAccessService.evaluate.
    private LocalDateTime closedAt;

    /**
     * Colour boards (PDFs) handed over from this project so far.
     *
     * The cap lives in configuration, not here, but the count has to be per PROJECT: the
     * plan's own pdfDownloadsUsed is a monthly figure across every room a shop touches,
     * and closing one job cannot be driven off that. Reset to zero when a closed project
     * is reopened, so a reopened job gets its boards back with its catalogue.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int colourBoardsUsed = 0;

    /**
     * AI renders this project may produce, and how many it has.
     *
     * One is included, and a project only ever earns the right to it by closing. Further
     * renders are bought one at a time; the allowance is stored per project rather than
     * as an account balance because a render is only meaningful against the eight combos
     * of the project that produced it.
     */
    @Column(nullable = false, columnDefinition = "integer not null default 1")
    @Builder.Default
    private int rendersAllowed = 1;

    @Column(nullable = false, columnDefinition = "integer not null default 0")
    @Builder.Default
    private int rendersUsed = 0;

    /** Has the customer finished with this project? */
    public boolean isClosed() {
        return closedAt != null;
    }

    /** Is there an AI render left to spend on this project? */
    public boolean hasRenderLeft() {
        return rendersUsed < rendersAllowed;
    }

    /** True when this project carries a paid window at all (running or paused). */
    public boolean hasAccessWindow() {
        return accessExpiresAt != null || accessPausedAt != null;
    }

    /**
     * Is the paid window currently open? A PAUSED window always reads as open: it is
     * only ever paused because the owner holds an active subscription, and that
     * subscription is what is granting access in the meantime.
     */
    public boolean isAccessWindowOpen() {
        if (accessPausedAt != null) return true;
        return accessExpiresAt != null && accessExpiresAt.isAfter(LocalDateTime.now());
    }

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<Region> regions = new ArrayList<>();

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
