package com.gridstore.huevista.project.controller;

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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "Projects", description = "Create and manage paint visualization projects")
public class ProjectController {

    private final ProjectService projectService;

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

    @Operation(summary = "List my projects", description = "Returns projects for the authenticated user, most recently updated first. Paged; defaults return the newest 200.")
    @ApiResponse(responseCode = "200", description = "Project list")
    @GetMapping
    public ResponseEntity<List<ProjectSummaryResponse>> getUserProjects(
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 200") @RequestParam(defaultValue = "200") int size,
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
                    - Body is optional. `maskMode` ("AUTO" default / "MANUAL")
                      chooses what happens AFTER the compulsory AI photo
                      clean-up: AUTO runs AI wall detection (consumes one
                      auto-mask credit; 402 AUTO_MASK_UNAVAILABLE when the plan
                      has none), MANUAL stops after the clean-up so walls are
                      marked by hand (free). `cleanImage: false` (ADMIN only)
                      skips the image-cleaner step. Masks are always stored
                      raw — exactly as the model produced them, with no
                      post-processing
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
        // maskMode is a real product choice open to everyone; the remaining
        // options are an ADMIN testing panel — silently stripped for every
        // other role so a crafted request can't alter the pipeline.
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        SegmentRequest effective = request;
        if (!admin && request != null) {
            effective = new SegmentRequest();
            effective.setMaskMode(request.getMaskMode());
        }
        return ResponseEntity.ok(projectService.requestSegmentation(
                userId(auth), id, effective));
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
            summary = "Delete a hand-drawn wall",
            description = """
                    Removes a region the user created by hand (manual = true). AI-detected
                    surfaces are protected and return 400 — only hand-drawn walls can be deleted.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Region deleted"),
            @ApiResponse(responseCode = "400", description = "Region is AI-detected, not hand-drawn"),
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

                    Valid durations: `3`, `7`, or `14` days (defaults to 7).

                    `brands` (optional, comma-separated brand names) limits which paint
                    companies the share viewer may repaint with; omit for all brands.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Share link with token and expiry")
    @PostMapping("/{id}/share")
    public ResponseEntity<ShareResponse> generateShareLink(
            @PathVariable String id,
            @Parameter(description = "Validity in days: 3, 7, or 14") @RequestParam(defaultValue = "7") int days,
            @Parameter(description = "Comma-separated paint company names the viewer may repaint with (blank = all)")
            @RequestParam(required = false) String brands,
            Authentication auth
    ) {
        if (days != 3 && days != 7 && days != 14) days = 7;
        java.util.List<String> brandList = (brands == null || brands.isBlank())
                ? java.util.List.of()
                : java.util.Arrays.stream(brands.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return ResponseEntity.ok(projectService.generateShareLink(userId(auth), id, days, brandList));
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

    @Operation(summary = "Claim guest projects after signing up",
            description = "Re-points projects created as a guest (owned by the redeemed access code) to the "
                    + "now-authenticated account. The shop keeps visibility via the code. Pass the guest token.")
    @ApiResponse(responseCode = "200", description = "Number of projects linked")
    @PostMapping("/claim-guest")
    public ResponseEntity<Map<String, Integer>> claimGuestProjects(
            @Valid @RequestBody GuestLinkRequest request,
            Authentication auth
    ) {
        int linked = projectService.linkGuestProjectsToUser(userId(auth), request.getGuestToken());
        return ResponseEntity.ok(Map.of("linked", linked));
    }

    private String userId(Authentication auth) {
        return auth.getName();
    }
}
