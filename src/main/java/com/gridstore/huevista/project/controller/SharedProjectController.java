package com.gridstore.huevista.project.controller;

import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
@Tag(name = "Shared Projects", description = "Public project view for end customers (no authentication required)")
public class SharedProjectController {

    private final ProjectService projectService;

    @Operation(
            summary = "View a shared project",
            description = """
                    Public endpoint — no authentication required.
                    Returns the project with applied hex colors visible but **shade codes hidden**.
                    Returns 404 if the token is invalid or has expired.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Shared project view")
    @ApiResponse(responseCode = "404", description = "Share link not found or expired")
    @SecurityRequirements
    @GetMapping("/{token}")
    public ResponseEntity<ProjectResponse> getSharedProject(@PathVariable String token) {
        return ResponseEntity.ok(projectService.getSharedProject(token));
    }

    @Operation(summary = "Shared project original image", description = "Public — streams the shared project's photo by token (used in local-storage mode where the normal image endpoint is owner-authenticated).")
    @SecurityRequirements
    @GetMapping("/{token}/image")
    public ResponseEntity<byte[]> getSharedImage(@PathVariable String token) {
        return stream(projectService.getSharedImage(token, false));
    }

    @Operation(summary = "Shared project cleaned image", description = "Public — streams the shared project's cleaned photo by token. 404 if there is no cleaned image.")
    @SecurityRequirements
    @GetMapping("/{token}/cleaned-image")
    public ResponseEntity<byte[]> getSharedCleanedImage(@PathVariable String token) {
        return stream(projectService.getSharedImage(token, true));
    }

    @Operation(summary = "Shared project region mask",
            description = "Public — streams a region's mask PNG by share token, so the share page can "
                    + "composite (and repaint) the room. 404 for an unknown region or a region without a mask.")
    @SecurityRequirements
    @GetMapping("/{token}/regions/{regionId}/mask")
    public ResponseEntity<byte[]> getSharedRegionMask(
            @PathVariable String token,
            @PathVariable Long regionId) {
        byte[] bytes = projectService.loadSharedRegionMaskBytes(token, regionId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(bytes);
    }

    @Operation(summary = "Claim a shared room as your own project",
            description = "Authenticated. Copies the shared room — its photo, its cleaned image and "
                    + "every wall mask — into the caller's account as a new project, and charges ONE "
                    + "project against whatever pays for them (their shop's code, their plan, or a "
                    + "project they bought). No AI runs: the walls already exist, so the copy is born "
                    + "segmented. 402 when there is no project left to spend.")
    @ApiResponse(responseCode = "200", description = "The caller's new copy of the room")
    @ApiResponse(responseCode = "402", description = "No project allowance left")
    @ApiResponse(responseCode = "404", description = "Share link not found or expired")
    @PostMapping("/{token}/claim")
    public ResponseEntity<ProjectResponse> claimSharedProject(
            @PathVariable String token,
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(projectService.claimSharedProject(userDetails.getUsername(), token));
    }

    private ResponseEntity<byte[]> stream(ProjectService.SharedImage image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(image.data());
    }
}
