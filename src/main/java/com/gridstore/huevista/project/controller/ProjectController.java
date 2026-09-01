package com.gridstore.huevista.project.controller;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.security.RequiresFeature;
import com.gridstore.huevista.common.ai.AiModelCatalogue;
import com.gridstore.huevista.project.dto.*;
import com.gridstore.huevista.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Create and manage paint visualization projects")
@RequiresFeature(AppFeature.STUDIO)
public class ProjectController {

    private final ProjectService projectService;
    private final AiModelCatalogue modelCatalogue;

    /**
     * Longest a share link may live. A share link is a repaint capability handed to
     * someone with no account, exactly like a walk-in access code — so it gets the same
     * 10-day ceiling rather than the old 14.
     */
    static final int SHARE_MAX_DAYS = 10;

    @Operation(summary = "Create a project", description = "Creates a new project from an uploaded image. The project starts in CREATED status — call /segment to run SAM 2.")
    @ApiResponse(responseCode = "201", description = "Project created")
    @ApiResponse(responseCode = "404", description = "Image not found or not owned by user")
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication auth
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createProject(userId(auth), request));
    }

    @Operation(summary = "List my projects", description = """
            Returns projects for the authenticated user, most recently updated first.

            For a RETAILER the list merges two sources — the shop's own rooms and the
            rooms its customers created under codes the shop issued — into one
            date-ordered sequence, each row tagged `OWN` or `CUSTOMER`. `size` caps the
            WHOLE response, not each source.
            """)
    @ApiResponse(responseCode = "200", description = "Project list")
    @GetMapping
    public ResponseEntity<List<ProjectSummaryResponse>> getUserProjects(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 500") @RequestParam(defaultValue = "400") int size,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.getUserProjects(userId(auth), page, size));
    }

    @Operation(summary = "Get project detail", description = "Returns full project detail including all segmented regions and their current colors.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project detail with regions"),
            @ApiResponse(responseCode = "404", description = "Project not found or not owned by user")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getProject(
            @PathVariable String id,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.getProject(userId(auth), id));
    }

    @Operation(
            summary = "Auto-save region colors",
            description = """
                    Updates the applied shade code and hex color for one or more regions.
                    Called by the frontend every 2 seconds while the user is applying colors.
                    Only the provided regions are updated; others are untouched.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Updated project with current region colors")
    @PutMapping("/{id}/regions")
    public ResponseEntity<Void> updateRegionColors(
            @PathVariable String id,
            @RequestBody List<RegionColorUpdate> updates,
            Authentication auth
    ) {
        projectService.updateRegionColors(userId(auth), id, updates);
        // 204: this is the per-swatch-click autosave — echoing the full project
        // (all regions + base64 masks) back on every colour change was the single
        // heaviest repeated payload in the studio.
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Save the paint plan",
            description = """
                    Says which surfaces are being painted, and what each one is in the scheme
                    (main wall / accent wall / another wall / trim). Called when the customer
                    closes the studio's Walls panel, not on every click.

                    Per-field PATCH: a null category, label or inPlan leaves that field as it
                    was. Excluding a wall keeps the region and its mask — it only takes the
                    surface out of the suggestions, out of "Apply all" and off the board — so
                    putting it back brings the room back exactly as it was.
                    """
    )
    @ApiResponse(responseCode = "204", description = "Plan saved")
    @PutMapping("/{id}/regions/plan")
    public ResponseEntity<Void> updateRegionPlan(
            @PathVariable String id,
            @RequestBody List<RegionPlanUpdate> updates,
            Authentication auth
    ) {
        projectService.updateRegionPlan(userId(auth), id, updates);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Update project details",
            description = "Partial update of name / room type / notes. Only provided fields change; a blank name is rejected.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated project"),
            @ApiResponse(responseCode = "400", description = "Blank name or field too long"),
            @ApiResponse(responseCode = "404", description = "Project not found or not owned by user")
    })
    @PatchMapping("/{id}")
    public ResponseEntity<ProjectResponse> updateProject(
            @PathVariable String id,
            @Valid @RequestBody UpdateProjectRequest request,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.updateProjectDetails(userId(auth), id, request));
    }

    @Operation(summary = "Delete a project", description = "Permanently deletes the project and all its regions.")
    @ApiResponse(responseCode = "204", description = "Deleted successfully")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable String id,
            Authentication auth
    ) {
        projectService.deleteProject(userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Run SAM 2 segmentation",
            description = """
                    Triggers asynchronous surface segmentation using SAM 2 via Replicate API.

                    - Returns immediately with status `SEGMENTING`
                    - Poll `GET /api/projects/{id}/status` every 1–2 seconds
                    - Status will change to `SEGMENTED` (masks ready) or `FAILED`
                    - Segmentation typically takes 30–90 seconds (image cleaning +
                      mask generation are generative model calls; slow runs can
                      take a few minutes, so poll with a generous deadline)
                    - Body is optional. Open to every signed-in caller:
                      `maskMode` ("AUTO" default / "MANUAL") chooses what
                      happens AFTER the compulsory AI photo clean-up — AUTO
                      runs AI wall detection (consumes one auto-mask credit;
                      402 AUTO_MASK_UNAVAILABLE when the plan has none),
                      MANUAL stops after the clean-up so walls are marked by
                      hand (free) — and `cleanFurnishing` ("KEEP" / "EMPTY")
                      and `cleanAngle` ("AS_SHOT" / "BEST_VIEW") shape the
                      clean-up itself. `analysePhoto` is an off switch, not a
                      request: omit it and the photo is looked at properly
                      before cleaning; send false to skip that. ADMIN only:
                      `cleanImage: false` skips the image-cleaner step,
                      `simulateFailure` (NONE / CLEAN / MASK / BOTH) makes the
                      image models decline for that half of the run so the
                      recovery paths can be tested on demand, `houseType`
                      overrides what the analysis decided, and
                      `cleanModel`/`maskModel` pin one run to a named model.
                      Masks are always stored raw — exactly as the model
                      produced them, with no post-processing
                    - AUTO does not always end in walls, and that is not a
                      failure: when the clean-up succeeds and wall detection
                      returns nothing, the project still comes back SEGMENTED
                      (on its cleaned canvas, with no regions) carrying
                      `autoMaskFailed: true`. The walls are the user's to mark
                      by hand, and a mask report has already been filed with
                      the admin. Only `FAILED` means there is nothing to open
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Segmentation started, status = SEGMENTING"),
            @ApiResponse(responseCode = "409", description = "Segmentation already in progress")
    })
    @PostMapping("/{id}/segment")
    public ResponseEntity<ProjectResponse> requestSegmentation(
            @PathVariable String id,
            @RequestBody(required = false) SegmentRequest request,
            Authentication auth
    ) {
        // Two groups, and the split is what the field DOES rather than who added it.
        //
        // Open to everyone: maskMode, the two clean-up choices the studio asks every
        // user before it sends the photo — what happens to the furniture, and which
        // camera the cleaned canvas is shot from — and analysePhoto, which is now an
        // off switch rather than a question (null means the photo is looked at). The
        // first two shape a picture the person in front of the screen is about to look
        // at, so the person in front of the screen is the one who should answer.
        //
        // ADMIN only: cleanImage (skip the clean entirely), simulateFailure (make the
        // models decline), cleanModel/maskModel (pin a supplier) and houseType (force
        // the analysis's answer). Every one of them exists to TEST the pipeline, and
        // three of them can make a run fail or cost a comparison; none belongs to a
        // customer's photo.
        //
        // Stripping by REBUILDING the request rather than nulling fields is what keeps
        // a knob added later admin-only by default: a new field is invisible to other
        // roles until someone copies it into the block below on purpose.
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        SegmentRequest effective = request;
        if (!admin && request != null) {
            effective = new SegmentRequest();
            effective.setMaskMode(request.getMaskMode());
            effective.setAnalysePhoto(request.getAnalysePhoto());
            effective.setCleanFurnishing(request.getCleanFurnishing());
            effective.setCleanAngle(request.getCleanAngle());
        }
        return ResponseEntity.ok(projectService.requestSegmentation(
                userId(auth), id, effective));
    }

    @Operation(
            summary = "The image models a run may be pinned to",
            description = """
                    The models an ADMIN may pick for the photo clean-up and for wall
                    detection (`cleanModel` / `maskModel` on the segment request), so two
                    can be compared on the same photo.

                    Served rather than hard-coded in the client on purpose: this is the
                    same list the segment endpoint validates against, so the studio can
                    only ever offer models the backend will actually run — including any
                    added through `replicate.selectable-models` without a deploy.

                    ROLE_ADMIN only. Sits above `/{id}` in the routing table because
                    "ai-models" is a literal path segment, not a project id.
                    """)
    @ApiResponse(responseCode = "200", description = "The selectable models, in display order")
    @GetMapping("/ai-models")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AiModelCatalogue.Option>> listAiModels() {
        return ResponseEntity.ok(modelCatalogue.options());
    }

    @Operation(
            summary = "Segment a specific point",
            description = """
                    Synchronously segments the surface at the given normalized coordinates (0-1).
                    Useful for manual wall selection — the user clicks a point on the image
                    and this returns the mask for that surface region.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "New region created from point"),
            @ApiResponse(responseCode = "404", description = "Project not found or not owned by user")
    })
    @PostMapping("/{id}/segment/point")
    public ResponseEntity<RegionResponse> segmentPoint(
            @PathVariable String id,
            @RequestBody PointSegmentRequest request,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.segmentPoint(
                userId(auth), id,
                request.getX(), request.getY(),
                request.getLabel()
        ));
    }

    @Operation(
            summary = "Save a hand-drawn mask as a region",
            description = """
                    Persists a mask the user drew by hand in the browser (polygon → PNG)
                    as a new region under the chosen category. No AI / Replicate call —
                    the client supplies the finished mask, so this works without SAM 2.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Region created from the drawn mask"),
            @ApiResponse(responseCode = "400", description = "Mask is missing or not a valid image"),
            @ApiResponse(responseCode = "404", description = "Project not found or not owned by user")
    })
    @PostMapping("/{id}/regions/custom-mask")
    public ResponseEntity<RegionResponse> createCustomMaskRegion(
            @PathVariable String id,
            @Valid @RequestBody CustomMaskRequest request,
            Authentication auth
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createCustomMaskRegion(userId(auth), id, request));
    }

    @Operation(
            summary = "Replace a region's mask with a hand-refined one",
            description = """
                    Overwrites an existing region's mask with one the user refined in
                    the browser. Works for AI-detected regions too — this is how a mask
                    the AI got wrong (half a pillar, an overshooting edge) is fixed after
                    segmentation. No AI / Replicate call; only the mask changes, the
                    region's category, label and applied colour are kept.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Region mask replaced"),
            @ApiResponse(responseCode = "400", description = "Mask is missing or not a valid image"),
            @ApiResponse(responseCode = "404", description = "Project or region not found / not owned")
    })
    @PutMapping("/{id}/regions/{regionId}/mask")
    public ResponseEntity<RegionResponse> updateRegionMask(
            @PathVariable String id,
            @PathVariable Long regionId,
            @Valid @RequestBody CustomMaskRequest request,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.updateRegionMask(userId(auth), id, regionId, request));
    }

    @Operation(
            summary = "Delete a wall",
            description = """
                    Removes a region from the project, whether it was drawn by hand or found
                    by wall detection. Detection routinely produces surfaces nobody wants
                    painted — an accent wall the customer is keeping, a ceiling, a strip of
                    floor read as wall — and those used to be permanent, carried in the wall
                    strip, the palette and every page of the colour board for the life of the
                    room.

                    The two are not equally cheap to undo: a hand-drawn wall can be redrawn
                    for nothing, while a detected one only comes back by re-running detection,
                    which spends a credit. The studio warns before removing a detected wall;
                    the API does not refuse it.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Region deleted"),
            @ApiResponse(responseCode = "404", description = "Project or region not found / not owned")
    })
    @DeleteMapping("/{id}/regions/{regionId}")
    public ResponseEntity<Void> deleteRegion(
            @PathVariable String id,
            @PathVariable Long regionId,
            Authentication auth
    ) {
        projectService.deleteRegion(userId(auth), id, regionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Poll segmentation status", description = "Returns the current project status and regions. Poll this every 1–2 s after calling /segment until status is SEGMENTED or FAILED.")
    @ApiResponse(responseCode = "200", description = "Current project status")
    @GetMapping("/{id}/status")
    public ResponseEntity<ProjectResponse> getStatus(
            @PathVariable String id,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.getStatus(userId(auth), id));
    }

    @Operation(
            summary = "Generate share link",
            description = """
                    Creates a time-limited public share link for the project.
                    The shared view shows applied colors but **hides shade codes** from the end customer.

                    Valid durations: `3`, `7`, or `10` days (defaults to 10).

                    A share link lets its holder repaint the room, so it is capped at the
                    same 10 days a shop access code gets — the two hand out the same thing
                    and should not outlive each other.

                    `brands` (optional, comma-separated brand names) limits which paint
                    companies the share viewer may repaint with; omit for all brands. A
                    shop may only name companies its distributor assigned it.

                    Calling this again REUSES the project's existing token and refreshes
                    its window — a link already forwarded keeps working. Use
                    `DELETE /{id}/share` to withdraw one deliberately.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Share link with token and expiry")
    @PostMapping("/{id}/share")
    public ResponseEntity<ShareResponse> generateShareLink(
            @PathVariable String id,
            @Parameter(description = "Validity in days: 3, 7, or 10 (max 10)")
            @RequestParam(defaultValue = "10") int days,
            @Parameter(description = "Comma-separated paint company names the viewer may repaint with (blank = all)")
            @RequestParam(required = false) String brands,
            Authentication auth
    ) {
        // Clamp rather than reject: an older client still asking for 14 gets the new
        // maximum instead of a 400 it has no way to interpret.
        if (days != 3 && days != 7) days = SHARE_MAX_DAYS;
        java.util.List<String> brandList = (brands == null || brands.isBlank())
                ? java.util.List.of()
                : java.util.Arrays.stream(brands.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ResponseEntity.ok(projectService.generateShareLink(userId(auth), id, days, brandList));
    }

    @Operation(
            summary = "Record and charge for a colour board",
            description = """
                    Reserves one colour-board download against whichever plan pays for the
                    caller, then records the pages that were on it — the shades, per region,
                    exactly as the customer received them.

                    Recording is the point. The PDF is built in the browser and the server
                    never sees it, so this is the only moment the combinations that went onto
                    paper can be captured; everything the closing flow does afterwards is
                    built on them.

                    When this was the project's last board (two by default, four images each)
                    the project CLOSES and the response says so — that is the signal to send
                    the customer on to choose a combination and render it.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Charged and recorded"),
            @ApiResponse(responseCode = "402", description = "The paying plan has no downloads left"),
            @ApiResponse(responseCode = "409", description = "The project is closed, or has no boards left")
    })
    @PostMapping("/{id}/colour-boards")
    public ResponseEntity<ColourBoardResponse> recordColourBoard(
            @PathVariable String id,
            @Valid @RequestBody RecordColourBoardRequest request,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.recordColourBoard(userId(auth), id, request));
    }

    @Operation(
            summary = "Close the project",
            description = """
                    Marks the job finished before it has spent both colour boards — the
                    customer saying "this is the one" rather than running out.

                    A closed project is view-only for everyone but an administrator, whatever
                    plan or access code is covering it, and only the combinations from its
                    colour boards stay visible. Reopening is a paid step and costs more than
                    a lapsed window does.

                    Idempotent: closing an already-closed project changes nothing.
                    """
    )
    @PostMapping("/{id}/close")
    public ResponseEntity<ProjectResponse> closeProject(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(projectService.closeProject(userId(auth), id));
    }

    @Operation(
            summary = "List the combinations this project handed over",
            description = "Every page of every colour board, in the order the customer saw "
                    + "them — the set a closed project still shows, and the set an AI render "
                    + "may be made from.")
    @GetMapping("/{id}/combos")
    public ResponseEntity<List<ProjectComboResponse>> getCombos(
            @PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(projectService.getCombos(userId(auth), id));
    }

    @Operation(
            summary = "Generate an AI render of one colour-board combination",
            description = """
                    Makes a photorealistic image of the room in one of the combinations this
                    project handed over, through Nano Banana Pro.

                    Only from a combination that was actually on one of this project's colour
                    boards — the picture shows a scheme the customer committed to on paper.
                    The project does NOT have to be closed.

                    EVERY image is paid for with AI image credits from the account's wallet.
                    There is no per-project included render and no per-project way to buy
                    one: a credit belongs to the account and works on any room it owns. The
                    credits are spent as the request is accepted and handed back if the image
                    cannot be made, so a failure never costs anything.

                    `sourceImage` chooses which photograph the model paints — CLEANED (the
                    default, and what every image made before the choice existed was given)
                    or ORIGINAL. A room with no cleaned photo gets its original either way.

                    Returns immediately with status QUEUED — poll the render until it reaches
                    READY or FAILED.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted; poll for the image"),
            @ApiResponse(responseCode = "402", description = "The AI credit wallet is short"),
            @ApiResponse(responseCode = "404", description = "No such project, or no such combination on it")
    })
    @PostMapping("/{id}/renders")
    public ResponseEntity<ProjectRenderResponse> requestRender(
            @PathVariable String id,
            @Valid @RequestBody CreateRenderRequest request,
            Authentication auth
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(projectService.requestRender(userId(auth), id, request));
    }

    @Operation(summary = "List this project's AI renders", description = "Newest first.")
    @GetMapping("/{id}/renders")
    public ResponseEntity<List<ProjectRenderResponse>> listRenders(
            @PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(projectService.listRenders(userId(auth), id));
    }

    @Operation(summary = "Poll one AI render",
            description = "The image URL appears once the status reaches READY; a FAILED "
                    + "render carries the reason and has already returned its credit.")
    @GetMapping("/{id}/renders/{renderId}")
    public ResponseEntity<ProjectRenderResponse> getRender(
            @PathVariable String id, @PathVariable String renderId, Authentication auth) {
        return ResponseEntity.ok(projectService.getRender(userId(auth), id, renderId));
    }

    @Operation(summary = "Withdraw the project's share link",
            description = "Invalidates the public link immediately. Sharing again mints a new "
                    + "token; until then the old URL answers 404.")
    @ApiResponse(responseCode = "204", description = "Share link withdrawn")
    @DeleteMapping("/{id}/share")
    public ResponseEntity<Void> revokeShareLink(@PathVariable String id, Authentication auth) {
        projectService.revokeShareLink(userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Stream a region's mask PNG",
            description = """
                    Same-origin proxy for the region mask. Use this when the S3 bucket
                    isn't CORS-configured for the frontend origin — the bytes are
                    streamed from S3 through the backend, so the browser sees a
                    same-origin response with no CORS preflight.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PNG mask bytes"),
            @ApiResponse(responseCode = "404", description = "Project or region not found, or region has no mask")
    })
    @GetMapping("/{id}/regions/{regionId}/mask")
    public ResponseEntity<byte[]> getRegionMask(
            @PathVariable String id,
            @PathVariable Long regionId,
            Authentication auth
    ) {
        byte[] bytes = projectService.loadRegionMaskBytes(userId(auth), id, regionId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(15)).cachePrivate())
                .body(bytes);
    }

    private String userId(Authentication auth) {
        return auth.getName();
    }
}
