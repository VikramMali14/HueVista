package com.gridstore.huevista.library.controller;

import com.gridstore.huevista.library.dto.*;
import com.gridstore.huevista.library.service.FreeProjectLibraryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The free-project library, admin-only for now.
 *
 * It lives under {@code /api/admin/**}, which SecurityConfig already restricts to
 * ROLE_ADMIN, so there is no second gate to keep in sync. Opening the gallery to
 * everyone later is a matter of adding a read-only controller on a public path —
 * the service draws no admin-specific conclusions, and "start a copy" is written
 * to work for any user id.
 */
@RestController
@RequestMapping("/api/admin/free-projects")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin · free projects",
        description = "Ready-made rooms published once and opened by anyone — ROLE_ADMIN only")
public class AdminFreeProjectController {

    private final FreeProjectLibraryService libraryService;

    @Operation(summary = "List templates",
            description = "The gallery, in display order. Includes unpublished ones by default so the admin sees the whole shelf.")
    @GetMapping
    public ResponseEntity<List<FreeProjectTemplateResponse>> list(
            @Parameter(description = "Set false to see only what a user would see")
            @RequestParam(defaultValue = "true") boolean includeUnpublished) {
        return ResponseEntity.ok(libraryService.listTemplates(includeUnpublished));
    }

    @Operation(summary = "Projects that could become templates",
            description = "The signed-in admin's own projects, each marked with whether it has walls to copy.")
    @GetMapping("/sources")
    public ResponseEntity<List<PublishableProjectResponse>> sources(Authentication auth) {
        return ResponseEntity.ok(libraryService.listPublishableProjects(auth.getName()));
    }

    @Operation(summary = "Publish a project into the library",
            description = """
                    Copies the project's photo and its existing masks once into the shared
                    library folder and shelves them under a room type. This is the only
                    point at which image work happens — every copy started afterwards
                    reuses these exact files and never calls the AI.
                    """)
    @PostMapping
    public ResponseEntity<FreeProjectTemplateResponse> publish(
            @Valid @RequestBody PublishTemplateRequest request, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(libraryService.publishFromProject(auth.getName(), request));
    }

    @Operation(summary = "Start a copy",
            description = """
                    Creates a project for the caller from the template: rows only, pointing at
                    the template's stored photo and masks. Nothing is uploaded, no mask is
                    generated, and no quota, plan credit or points are spent.
                    """)
    @PostMapping("/{templateId}/start")
    public ResponseEntity<StartedProjectResponse> start(@PathVariable String templateId, Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(libraryService.startFromTemplate(auth.getName(), templateId));
    }

    @Operation(summary = "Show or hide a template",
            description = "Hidden templates stay in this list but are refused by 'start a copy'. Files are untouched.")
    @PatchMapping("/{templateId}/published")
    public ResponseEntity<FreeProjectTemplateResponse> setPublished(
            @PathVariable String templateId,
            @RequestParam boolean published,
            Authentication auth) {
        return ResponseEntity.ok(libraryService.setPublished(auth.getName(), templateId, published));
    }

    @Operation(summary = "Delete a template",
            description = """
                    Takes it off the shelf. By default the stored files are KEPT, because copies
                    already in people's accounts point at them and would otherwise go blank —
                    pass purgeFiles=true only when you know nobody is holding one. The response
                    says which of the two happened, and how many copies it cost.
                    """)
    @DeleteMapping("/{templateId}")
    public ResponseEntity<TemplateDeletionResponse.Removed> delete(
            @PathVariable String templateId,
            @Parameter(description = "Also delete the shared photo and masks. Breaks existing copies.")
            @RequestParam(defaultValue = "false") boolean purgeFiles,
            Authentication auth) {
        return ResponseEntity.ok(libraryService.deleteTemplate(auth.getName(), templateId, purgeFiles));
    }

    @Operation(summary = "Delete several templates",
            description = """
                    The same operation over a selection. Each template is removed independently,
                    so one that has already gone is reported as a failure rather than abandoning
                    the rest. purgeFiles applies to the whole selection.
                    """)
    @PostMapping("/delete")
    public ResponseEntity<TemplateDeletionResponse> deleteMany(
            @Valid @RequestBody DeleteTemplatesRequest request, Authentication auth) {
        return ResponseEntity.ok(
                libraryService.deleteTemplates(auth.getName(), request.getTemplateIds(),
                        Boolean.TRUE.equals(request.getPurgeFiles())));
    }
}
