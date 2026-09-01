package com.gridstore.huevista.guest.controller;

import com.gridstore.huevista.billing.dto.PdfAllowanceResponse;
import com.gridstore.huevista.billing.service.PdfQuotaService;
import com.gridstore.huevista.image.dto.ImageResponse;
import com.gridstore.huevista.image.service.ImageService;
import com.gridstore.huevista.maskreport.dto.CreateMaskReportRequest;
import com.gridstore.huevista.maskreport.dto.MaskReportResponse;
import com.gridstore.huevista.maskreport.service.MaskReportService;
import com.gridstore.huevista.project.dto.ColourBoardResponse;
import com.gridstore.huevista.project.dto.CreateProjectRequest;
import com.gridstore.huevista.project.dto.CustomMaskRequest;
import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.dto.ProjectSummaryResponse;
import com.gridstore.huevista.project.dto.RecordColourBoardRequest;
import com.gridstore.huevista.project.dto.RegionColorUpdate;
import com.gridstore.huevista.project.dto.RegionPlanUpdate;
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
    private final PdfQuotaService pdfQuotaService;
    private final MaskReportService maskReportService;

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

    @Operation(summary = "Replace a region's mask with a hand-refined one (guest)",
            description = "Overwrites an existing region's mask — including an AI-detected one — "
                    + "with a version the guest refined by hand. No AI call; only the mask changes.")
    @PutMapping("/projects/{id}/regions/{regionId}/mask")
    public ResponseEntity<RegionResponse> updateRegionMask(
            @PathVariable String id,
            @PathVariable Long regionId,
            @Valid @RequestBody CustomMaskRequest request,
            Authentication auth) {
        return ResponseEntity.ok(projectService.updateGuestRegionMask(accessCodeId(auth), id, regionId, request));
    }

    @Operation(summary = "Apply colours to regions (guest)")
    @PutMapping("/projects/{id}/regions")
    public ResponseEntity<Void> recolor(
            @PathVariable String id,
            @RequestBody List<RegionColorUpdate> updates,
            Authentication auth) {
        // 204 — same featherweight autosave contract as the signed-in path.
        projectService.updateGuestRegionColors(accessCodeId(auth), id, updates);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Save the paint plan (guest)",
            description = "Which surfaces are being painted and what each one is in the scheme. "
                    + "See the owner endpoint for the per-field PATCH rules.")
    @PutMapping("/projects/{id}/regions/plan")
    public ResponseEntity<Void> updateRegionPlan(
            @PathVariable String id,
            @RequestBody List<RegionPlanUpdate> updates,
            Authentication auth) {
        projectService.updateGuestRegionPlan(accessCodeId(auth), id, updates);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Delete a wall (guest)",
            description = "Removes a region, hand-drawn or AI-detected. See the owner endpoint for why "
                    + "detected walls are removable.")
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

    @Operation(summary = "Report an AI run that came out wrong (guest)",
            description = "Same channel as the signed-in studio's report button. The guest has no "
                    + "account, so the report is filed against the access code and the admin "
                    + "follows up through the shop that issued it.")
    @PostMapping("/projects/{id}/mask-reports")
    public ResponseEntity<MaskReportResponse> reportMask(
            @PathVariable String id,
            @Valid @RequestBody CreateMaskReportRequest request,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(maskReportService.reportAsGuest(accessCodeId(auth), id, request));
    }

    @Operation(summary = "Get the colour-board PDF allowance (guest) — the issuing shop's quota")
    @GetMapping("/pdf-allowance")
    public ResponseEntity<PdfAllowanceResponse> pdfAllowance(Authentication auth) {
        return ResponseEntity.ok(pdfQuotaService.allowanceForGuest(accessCodeId(auth)));
    }

    @Operation(summary = "Charge one colour-board PDF download (guest) — billed to the issuing shop",
            description = "Atomically reserves one PDF download against the issuing shop's plan. "
                    + "402 when the shop's monthly PDF limit is spent.")
    @PostMapping("/pdf-downloads")
    public ResponseEntity<PdfAllowanceResponse> chargePdfDownload(Authentication auth) {
        return ResponseEntity.ok(pdfQuotaService.reserveForGuest(accessCodeId(auth)));
    }

    @Operation(summary = "Record and charge for a colour board (guest)",
            description = "The guest twin of the account holder's endpoint: reserves one "
                    + "download against whoever the access code says pays, records the shades "
                    + "that were on each page, and closes the project when it was the last "
                    + "board. Prefer this over /pdf-downloads — a board charged through that "
                    + "one is not recorded and never closes anything.")
    @PostMapping("/projects/{id}/colour-boards")
    public ResponseEntity<ColourBoardResponse> recordColourBoard(
            @PathVariable String id,
            @Valid @RequestBody RecordColourBoardRequest request,
            Authentication auth) {
        return ResponseEntity.ok(
                projectService.recordGuestColourBoard(accessCodeId(auth), id, request));
    }

    /** For a guest, the principal name is the access code id (set by GuestAuthFilter). */
    private String accessCodeId(Authentication auth) {
        return auth.getName();
    }
}
