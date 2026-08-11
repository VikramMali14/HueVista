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
    // "AUTO" / "MANUAL" — the wall-creation choice this project was (last)
    // segmented with; null = default AUTO. MANUAL projects come back SEGMENTED
    // with zero auto regions: the cleaned canvas is ready and the user marks
    // walls themselves.
    private String maskMode;
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
    /** What reopening this project costs, in POINTS. */
    private int reopenPricePoints;

    /** Stamp the viewer's access onto an owner-view response. */
    public ProjectResponse withAccess(boolean readOnly, String reason,
                                      LocalDateTime accessExpiresAt, int reopenPricePoints) {
        this.readOnly = readOnly;
        this.readOnlyReason = reason;
        this.accessExpiresAt = accessExpiresAt;
        this.reopenPricePoints = reopenPricePoints;
        return this;
    }

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
                .failureReason(project.getFailureReason())
                .maskMode(project.getMaskMode())
                .regions(regions)
                .hasShareLink(project.getShareToken() != null)
                .shareExpiresAt(project.getShareExpiresAt())
                .sentToShopAt(project.getSentToShopAt())
                .closedAt(project.getClosedAt())
                .boardsUsed(project.getColourBoardsUsed())
                .boardsAllowed(boardsAllowed)
                .rendersAllowed(project.getRendersAllowed())
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
                .regions(regions)
                .sharedBrands(project.getShareBrandList())
                // The guest needs this to render "Sent ✓" after a reload.
                .sentToShopAt(project.getSentToShopAt())
                .build();
    }
}
