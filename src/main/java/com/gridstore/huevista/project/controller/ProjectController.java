package com.gridstore.huevista.project.controller;

import com.gridstore.huevista.project.dto.*;
import com.gridstore.huevista.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /** POST /api/projects — create a new project from an uploaded image */
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.createProject(userId(auth), request));
    }

    /** GET /api/projects — list all projects for the authenticated user */
    @GetMapping
    public ResponseEntity<List<ProjectSummaryResponse>> getUserProjects(Authentication auth) {
        return ResponseEntity.ok(projectService.getUserProjects(userId(auth)));
    }

    /** GET /api/projects/{id} — get full project detail with regions */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getProject(
            @PathVariable String id,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.getProject(userId(auth), id));
    }

    /**
     * PUT /api/projects/{id}/regions — auto-save region color assignments.
     * Called by the frontend every 2 seconds while the user is working.
     */
    @PutMapping("/{id}/regions")
    public ResponseEntity<ProjectResponse> updateRegionColors(
            @PathVariable String id,
            @RequestBody List<RegionColorUpdate> updates,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.updateRegionColors(userId(auth), id, updates));
    }

    /** DELETE /api/projects/{id} — permanently delete a project and its regions */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProject(
            @PathVariable String id,
            Authentication auth
    ) {
        projectService.deleteProject(userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/projects/{id}/segment — trigger SAM 2 auto-segmentation.
     * Returns immediately with status SEGMENTING; poll /status for completion.
     */
    @PostMapping("/{id}/segment")
    public ResponseEntity<ProjectResponse> requestSegmentation(
            @PathVariable String id,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.requestSegmentation(userId(auth), id));
    }

    /** GET /api/projects/{id}/status — poll segmentation job status */
    @GetMapping("/{id}/status")
    public ResponseEntity<ProjectResponse> getStatus(
            @PathVariable String id,
            Authentication auth
    ) {
        return ResponseEntity.ok(projectService.getStatus(userId(auth), id));
    }

    /**
     * POST /api/projects/{id}/share?days=7 — generate a shareable link.
     * Valid for 3, 7, or 14 days (defaults to 7).
     */
    @PostMapping("/{id}/share")
    public ResponseEntity<ShareResponse> generateShareLink(
            @PathVariable String id,
            @RequestParam(defaultValue = "7") int days,
            Authentication auth
    ) {
        if (days != 3 && days != 7 && days != 14) days = 7;
        return ResponseEntity.ok(projectService.generateShareLink(userId(auth), id, days));
    }

    private String userId(Authentication auth) {
        return auth.getName(); // username = userId (set by JwtAuthFilter)
    }
}
