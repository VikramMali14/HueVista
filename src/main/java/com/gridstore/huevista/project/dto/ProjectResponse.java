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
    /**
     * What to say about {@link #autoMaskFailed}, so the studio does not have to invent it.
     *
     * <p>Two facts, in the order they matter to the person reading. First that the WALLS
     * are what failed — the photo itself is cleaned and waiting, which is the expensive
     * half and the half they can still use. Second that the report has already gone: the
     * pipeline files its own mask report here, precisely because somebody looking at a
     * working room has no reason to file one, and saying so is the difference between
     * "something broke" and "something broke and it is being dealt with".
     *
     * <p>Null unless {@code autoMaskFailed}. It lives here rather than in the frontend
     * because the share view, the studio and the kiosk all show this state and had no
     * shared wording between them.
     */
    private String autoMaskNotice;
    /**
     * What the AI run is doing right now, while {@code status} is SEGMENTING.
     *
     * <p>The pipeline works through a chain of models and hands over whenever one is
     * busy, which used to be invisible: one unchanging spinner for anything from forty
     * seconds to eight minutes, so a working run and a dead one looked identical. This
     * carries the running commentary — "That model was busy — trying Nano Banana 2
     * (2 of 4)" — and is null on any project that is not mid-run.
     */
    private String aiProgressNote;
    // The image models this project's last run was PINNED to by an admin comparing
    // models, or null (the overwhelmingly normal case) for the configured ones. Carried
    // so the admin mask viewer can say which models made the canvas and the mask it is
    // showing — a comparison whose result nobody can attribute afterwards was not one.
    private String cleanModel;
    private String maskModel;
    // What a closer look at the photo found, when an admin asked for one — see
    // ClaudeVisionService.analyseStored. Null on every project that never asked, which
    // is every customer project.
    //
    // houseType is the kind of place ("BATHROOM", "COMPOUND_WALL"): it decides which
    // extra sentences the cleaning and mask prompts get, so the studio has to be able to
    // show WHICH type actually ran — a prompt experiment nobody can attribute afterwards
    // was not one, exactly as with cleanModel above.
    private String houseType;
    // The colour the walls are RIGHT NOW, as they appear under the photo's own light,
    // plus the model's everyday name for it. Shown beside the palette as context, never
    // used as a paint colour: the cleaned canvas stays white because the frontend treats
    // it as an illumination map. Null whenever no colour could be read honestly — an
    // unpainted wall, deep shadow — and null is the right answer there, because this
    // sits next to catalogue shades a customer may be about to buy.
    private String detectedWallHex;
    private String detectedWallColour;
    private String detectedTrimHex;
    // The prompt knobs this project's last run used: "KEEP"/"EMPTY" for the furniture
    // and "AS_SHOT"/"BEST_VIEW" for the camera. Null = the defaults, which produce the
    // stock prompt. Carried for the same reason as the model overrides — so a canvas
    // that came back unlike the others can be traced to the choice that made it.
    private String cleanFurnishing;
    private String cleanAngle;
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
    // AI images this project has produced. A count, not an allowance: there is no per-
    // project entitlement any more and no price to quote for one, because an AI image is
    // bought with an AI credit from the ACCOUNT's wallet. The studio reads what an image
    // costs off that wallet, which is the only thing that knows.
    private int rendersUsed;

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

    /**
     * Projects this account has already paid for and not yet started — the third way out
     * of a locked room, and the only one that costs nothing new.
     *
     * <p>Quoted on the project rather than left to the studio to fetch because it belongs
     * beside the two prices above: all three answer "what would it take to work on THIS
     * room again", and a banner that reads two of them from the project and the third from
     * a separate call can show a price for a rail that is gone and no offer for one that is
     * there. Zero while the project is fully open — there is nothing to spend a credit on.
     */
    private int reopenCredits;

    /** Stamp the viewer's access onto an owner-view response. */
    public ProjectResponse withAccess(boolean readOnly, String reason,
                                      LocalDateTime accessExpiresAt,
                                      int reopenPricePoints, int reopenPricePaise,
                                      int reopenCredits) {
        this.readOnly = readOnly;
        this.readOnlyReason = reason;
        this.accessExpiresAt = accessExpiresAt;
        this.reopenPricePoints = reopenPricePoints;
        this.reopenPricePaise = reopenPricePaise;
        this.reopenCredits = reopenCredits;
        return this;
    }

    /** The sentence behind {@link #autoMaskNotice}; see that field for the reasoning. */
    static final String AUTO_MASK_NOTICE =
            "We couldn't create the custom wall masks for this photo — the issue has been "
            + "sent to our tech team and they'll look at it. Your cleaned photo is ready, "
            + "so mark the walls yourself with \"Add a wall\" (free and unlimited) and "
            + "carry on painting.";

    public static ProjectResponse from(Project project, String imageUrl) {
        return from(project, imageUrl, 0);
    }

    public static ProjectResponse from(Project project, String imageUrl, int boardsAllowed) {
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
                .autoMaskNotice(project.isAutoMaskFailed() ? AUTO_MASK_NOTICE : null)
                .aiProgressNote(project.getAiProgressNote())
                .cleanModel(project.getCleanModel())
                .maskModel(project.getMaskModel())
                // The photo's own analysis, and the knobs this run was prompted with.
                // Owner view only — fromPublic below carries none of it, because a
                // shared board is a colour scheme rather than a look inside how it was
                // made. Every field is null on a project that never asked for any of it.
                .houseType(project.getImage().getHouseType() != null
                        ? project.getImage().getHouseType().name() : null)
                .detectedWallHex(project.getImage().getDetectedWallHex())
                .detectedWallColour(project.getImage().getDetectedWallColour())
                .detectedTrimHex(project.getImage().getDetectedTrimHex())
                .cleanFurnishing(project.getCleanFurnishing())
                .cleanAngle(project.getCleanAngle())
                .regions(regions)
                .hasShareLink(project.getShareToken() != null)
                .shareExpiresAt(project.getShareExpiresAt())
                .sentToShopAt(project.getSentToShopAt())
                .fromLibrary(project.isFromLibrary())
                .closedAt(project.getClosedAt())
                .boardsUsed(project.getColourBoardsUsed())
                .boardsAllowed(boardsAllowed)
                .rendersUsed(project.getRendersUsed())
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
