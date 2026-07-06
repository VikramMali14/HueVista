package com.gridstore.huevista.guest.controller;

import com.gridstore.huevista.image.dto.ImageResponse;
import com.gridstore.huevista.image.service.ImageService;
import com.gridstore.huevista.project.dto.CreateProjectRequest;
import com.gridstore.huevista.project.dto.CustomMaskRequest;
import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.dto.ProjectSummaryResponse;
import com.gridstore.huevista.project.dto.RegionColorUpdate;
import com.gridstore.huevista.project.dto.RegionResponse;
import com.gridstore.huevista.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Guest (anonymous) project creator. Every endpoint requires a ROLE_GUEST token
 * issued by redeeming a shop access code; the principal name IS the access code id,
 * which scopes ownership of the guest's single image + project. Responses hide real
 * shade codes — the issuing shop resolves those from the code.
 */
@RestController
@RequestMapping("/api/guest")
@RequiredArgsConstructor
@Tag(name = "Guest", description = "Anonymous, access-code-scoped project creator (no account)")
public class GuestController {

    private final ImageService imageService;
    private final ProjectService projectService;

    @Operation(summary = "Upload a room photo (guest)")
    @PostMapping(value = "/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageResponse> upload(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(imageService.uploadForGuest(file, accessCodeId(auth)));
    }

    @Operation(summary = "Create the guest's single project")
    @PostMapping("/projects")
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createGuestProject(accessCodeId(auth), request));
    }

    @Operation(summary = "List the guest's project(s)")
    @GetMapping("/projects")
    public ResponseEntity<List<ProjectSummaryResponse>> list(Authentication auth) {
        return ResponseEntity.ok(projectService.getGuestProjects(accessCodeId(auth)));
    }

    @Operation(summary = "Get the guest's project (shade codes hidden)")
    @GetMapping("/projects/{id}")
    public ResponseEntity<ProjectResponse> get(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(projectService.getGuestProject(accessCodeId(auth), id));
    }

    @Operation(summary = "Run AI wall-detection (guest) — billed to the issuing shop's quota",
            description = "Triggers asynchronous wall segmentation for the guest's project. The Replicate "
                    + "cost is charged to the issuing shop's monthly AI quota; returns 402 when the shop "
                    + "is out of credits, in which case the guest marks walls by hand instead.")
    @PostMapping("/projects/{id}/segment")
    public ResponseEntity<ProjectResponse> segment(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(projectService.requestGuestSegmentation(accessCodeId(auth), id));
    }

    @Operation(summary = "Save a hand-drawn region mask (guest)")
    @PostMapping("/projects/{id}/regions/custom-mask")
    public ResponseEntity<RegionResponse> customMask(
            @PathVariable String id,
            @Valid @RequestBody CustomMaskRequest request,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(projectService.createGuestCustomMaskRegion(accessCodeId(auth), id, request));
    }

    @Operation(summary = "Apply colours to regions (guest)")
    @PutMapping("/projects/{id}/regions")
    public ResponseEntity<ProjectResponse> recolor(
            @PathVariable String id,
            @RequestBody List<RegionColorUpdate> updates,
            Authentication auth) {
        return ResponseEntity.ok(projectService.updateGuestRegionColors(accessCodeId(auth), id, updates));
    }

    @Operation(summary = "Delete a hand-drawn wall (guest)")
    @DeleteMapping("/projects/{id}/regions/{regionId}")
    public ResponseEntity<Void> deleteRegion(
            @PathVariable String id,
            @PathVariable Long regionId,
            Authentication auth) {
        projectService.deleteGuestRegion(accessCodeId(auth), id, regionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Send the project to the issuing shop",
            description = "Marks the guest's project as sent (idempotent) so the counter knows the "
                    + "customer is done; the shop owner gets a best-effort email heads-up.")
    @PostMapping("/projects/{id}/send-to-shop")
    public ResponseEntity<ProjectResponse> sendToShop(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(projectService.sendGuestProjectToShop(accessCodeId(auth), id));
    }

    /** For a guest, the principal name is the access code id (set by GuestAuthFilter). */
    private String accessCodeId(Authentication auth) {
        return auth.getName();
    }
}
