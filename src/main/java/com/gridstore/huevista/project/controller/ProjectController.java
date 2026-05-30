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

    @Operation(summary = "List my projects", description = "Returns all projects for the authenticated user, most recently updated first.")
    @ApiResponse(responseCode = "200", description = "Project list")
    @GetMapping
    public ResponseEntity<List<ProjectSummaryResponse>> getUserProjects(Authentication auth) {
        return ResponseEntity.ok(projectService.getUserProjects(userId(auth)));
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
    public ResponseEntity<ProjectResponse> updateRegionColors(
            @PathVariable String id,
            @RequestBody List<RegionColorUpdate> updates,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.updateRegionColors(userId(auth), id, updates));
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
                    - Segmentation typically takes 2–8 seconds
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Segmentation started, status = SEGMENTING"),
            @ApiResponse(responseCode = "400", description = "Segmentation already in progress")
    })
    @PostMapping("/{id}/segment")
    public ResponseEntity<ProjectResponse> requestSegmentation(
            @PathVariable String id,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.requestSegmentation(userId(auth), id));
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
                    """
    )
    @ApiResponse(responseCode = "200", description = "Share link with token and expiry")
    @PostMapping("/{id}/share")
    public ResponseEntity<ShareResponse> generateShareLink(
            @PathVariable String id,
            @Parameter(description = "Validity in days: 3, 7, or 14") @RequestParam(defaultValue = "7") int days,
            Authentication auth
    ) {
        if (days != 3 && days != 7 && days != 14) days = 7;
        return ResponseEntity.ok(projectService.generateShareLink(userId(auth), id, days));
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
