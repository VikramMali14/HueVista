package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.Project;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ProjectResponse {

    private String id;
    private String name;
    private String roomType;
    private String notes;
    private String status;
    private String imageId;
    private String imageUrl;
    /**
     * "INDOOR" / "OUTDOOR" / "UNKNOWN" — the scene the pipeline actually ran this
     * project as.
     *
     * The studio used to know this only from the upload response, which meant it knew
     * it for exactly one moment: a REOPENED project showed no scene at all, and a
     * GUEST project showed UNKNOWN forever, because the kiosk upload skips
     * classification and the answer segmentation later worked out never came back.
     * The frontend branches on it too (outdoor rooms get different colour advice), so
     * the two ends were reasoning about different photos.
     */
    private String imageType;
    // Cleaned image URL when ImageCleanerService ran. Frontend should
    // prefer this as the paint canvas when present — masks are aligned
    // to the cleaned image, not the original. Null when cleaning is
    // disabled or hasn't run.
    private String cleanedImageUrl;
    // The model's raw colour-coded mask (RED/GREEN/BLUE/BLACK) from the
    // accepted generation — diagnostics for the admin mask viewer. Only set
    // on the owner view (never the shared/public view); null for projects
    // segmented before this shipped or with manual-only regions.
    private String rawMaskUrl;
    // Populated when status == FAILED so the UI can show the cause.
    private String failureReason;
    // "CLEAN" / "MASK" — which half of the run failed, so the studio can offer to
    // report it with the right problem already ticked. Null when the run didn't
    // fail, when it failed for a reason that is ours rather than the models'
    // (missing token), or on projects that failed before this shipped.
    private String failureStage;
    // "AUTO" / "MANUAL" — the wall-creation choice this project was (last)
    // segmented with; null = default AUTO. MANUAL projects come back SEGMENTED
    // with zero auto regions: the cleaned canvas is ready and the user marks
    // walls themselves.
    private String maskMode;
    // True when THIS project asked for AI wall detection, got its cleaned photo, and
    // the mask model still found nothing usable. The project is SEGMENTED and fully
    // workable — the cleaned canvas is there — but it carries no auto walls, so the
    // studio asks for them by hand. The team has already been told: the pipeline files
    // its own report in this case, because a user with a working room has no reason to.
    private boolean autoMaskFailed;
    private List<RegionResponse> regions;
    private boolean hasShareLink;
    private LocalDateTime shareExpiresAt;
    // Shared/public view only: brand names the retailer opened for the share
    // viewer's repaint palette. Empty = every brand. Null on the owner view.
    private List<String> sharedBrands;
    // Shared/public view only: how the issuing shop presents a colour — its code
    // pattern, and whether paint names are shown at all. The share viewer has no
    // session, so this travels with the project rather than being asked for.
    // Null on the owner view, which reads /api/me/shade-code-scheme instead.
    private com.gridstore.huevista.paint.dto.ShadeCodeSchemeResponse shadeCodeScheme;
    // When the customer sent the project to the issuing shop; null until then.
    private LocalDateTime sentToShopAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ─── Closing ─────────────────────────────────────────────────────────────
    // When the job finished — by the customer closing it, or by its last colour board.
    // Null while it is still running. A closed project is view-only whatever else is
    // covering it, and the studio shows only the combinations from its boards.
    private LocalDateTime closedAt;
    // Colour boards handed over, and how many this project gets. The studio counts down
    // with these so "one board left" can be said before the last one closes the project.
    private int boardsUsed;
    private int boardsAllowed;
    // AI renders this project may still produce, and how many it has. One is included
    // and unlocked by closing; the rest are bought one at a time.
    private int rendersAllowed;
    private int rendersUsed;
    // What one more costs, in paise. Quoted here rather than left for the studio to infer
    // from the reopen price they happen to share today: they are two settings, and a
    // button that names a price the payment then refuses is worse than no price at all.
    private int renderPricePaise;

    /**
     * True when this room was copied off the free library shelf.
     *
     * The studio uses it to drop the whole closing apparatus — the "Close project" button,
     * the boards-left countdown, the validity banner — none of which is true here: a
     * library room has no board cap, never closes and can never lapse. Offering a button
     * whose only effect would be to lock a free room, on a server that now refuses to,
     * is worse than not offering it.
     */
    private boolean fromLibrary;

    // ─── Access ──────────────────────────────────────────────────────────────
    // True when the viewer may look but not touch: the colours last applied are all
    // here and render normally, but every write is refused. The studio uses this to
    // disable the palette rather than letting the user paint and then fail on save.
    private boolean readOnly;
    // Why, in a sentence fit to show. Null when the project is fully open.
    private String readOnlyReason;
    // When this project's paid validity runs out. Null when it has no window of its
    // own (covered by a plan or a shop's access code) or while that window is paused.
    private LocalDateTime accessExpiresAt;
    // What reopening a lapsed project costs, in paise — so the studio can name the
    // price on the banner instead of sending the user off to find it.
    /** What reopening THIS project costs, on both rails. Read from the project rather
     *  than from the account: a lapsed window and a closed project are two different
     *  purchases at two different prices, and only the project knows which this is. */
    private int reopenPricePoints;
    private int reopenPricePaise;

    /** Stamp the viewer's access onto an owner-view response. */
    public ProjectResponse withAccess(boolean readOnly, String reason,
                                      LocalDateTime accessExpiresAt,
                                      int reopenPricePoints, int reopenPricePaise) {
        this.readOnly = readOnly;
        this.readOnlyReason = reason;
        this.accessExpiresAt = accessExpiresAt;
        this.reopenPricePoints = reopenPricePoints;
        this.reopenPricePaise = reopenPricePaise;
        return this;
    }

    public static ProjectResponse from(Project project, String imageUrl) {
        return from(project, imageUrl, 0, 0);
    }

    public static ProjectResponse from(Project project, String imageUrl,
                                       int boardsAllowed, int renderPricePaise) {
        List<RegionResponse> regions = project.getRegions().stream()
                .map(RegionResponse::from)
                .toList();

        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .roomType(project.getRoomType())
                .notes(project.getNotes())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .imageType(project.getImage().getImageType() != null
                        ? project.getImage().getImageType().name() : null)
                .failureReason(project.getFailureReason())
                .failureStage(project.getFailureStage() != null
                        ? project.getFailureStage().name() : null)
                .maskMode(project.getMaskMode())
                .autoMaskFailed(project.isAutoMaskFailed())
                .regions(regions)
                .hasShareLink(project.getShareToken() != null)
                .shareExpiresAt(project.getShareExpiresAt())
                .sentToShopAt(project.getSentToShopAt())
                .fromLibrary(project.isFromLibrary())
                .closedAt(project.getClosedAt())
                .boardsUsed(project.getColourBoardsUsed())
                .boardsAllowed(boardsAllowed)
                .rendersAllowed(project.getRendersAllowed())
                .rendersUsed(project.getRendersUsed())
                .renderPricePaise(renderPricePaise)
                .createdAt(project.getCreatedAt())
                .updatedAt(project.getUpdatedAt())
                .build();
    }

    // Shared/public view — region shade codes hidden
    public static ProjectResponse fromPublic(Project project, String imageUrl) {
        List<RegionResponse> regions = project.getRegions().stream()
                .map(RegionResponse::fromPublic)
                .toList();

        return ProjectResponse.builder()
                .id(project.getId())
                .name(project.getName())
                .status(project.getStatus().name())
                .imageId(project.getImage().getId())
                .imageUrl(imageUrl)
                .imageType(project.getImage().getImageType() != null
                        ? project.getImage().getImageType().name() : null)
                .regions(regions)
                .sharedBrands(project.getShareBrandList())
                // The guest needs this to render "Sent ✓" after a reload.
                .sentToShopAt(project.getSentToShopAt())
                .build();
    }
}
