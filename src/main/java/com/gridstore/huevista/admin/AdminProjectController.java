package com.gridstore.huevista.admin;

import com.gridstore.huevista.project.dto.AdminProjectRow;
import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only access to EVERY room on the platform, for the admin mask viewer.
 *
 * <p>The rest of the project API is scoped to whoever is asking, which is right for every
 * other caller and wrong for this one. A run that puts the walls in the wrong places
 * still returns SEGMENTED and passes every check the backend makes, so the only signal
 * it happened is a user pressing "Report a problem" — and following that report up means
 * opening a room that belongs to somebody else, often a walk-in customer at a shop with
 * no account at all. Without this the mask viewer could only inspect the admin's own test
 * uploads, which are the one set of rooms nobody ever reports.
 *
 * <p>Strictly read-only: there is no write endpoint here, and the detail response comes
 * back flagged read-only so the studio keeps its save paths shut. ROLE_ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin projects", description = "Every room, for mask diagnostics — ROLE_ADMIN only")
public class AdminProjectController {

    private final ProjectService projectService;

    @Operation(summary = "List every project", description = """
            Every room on the platform, newest first, whoever owns it — including rooms
            owned by an access code alone, which have no user account behind them.

            Each row carries who it belongs to (account, shop, code) so a reported room
            can be found among everyone else's.
            """)
    @ApiResponse(responseCode = "200", description = "Project rows")
    @GetMapping
    public ResponseEntity<List<AdminProjectRow>> listProjects(
            @Parameter(description = "Matches room name or id, owner name or e-mail, shop name, or access code")
            @RequestParam(required = false) String q,
            @Parameter(description = "Zero-based page index") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size, max 200") @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(projectService.searchAllProjects(q, page, size));
    }

    @Operation(summary = "Get any project's detail", description = """
            Full detail for one room — both canvases (original and cleaned) and every
            stored region mask — regardless of who owns it or whether their access has
            lapsed. A lapsed or closed room is exactly the kind that gets reported.

            Always answers read-only.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Project detail with regions"),
            @ApiResponse(responseCode = "404", description = "No such project")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable String id) {
        return ResponseEntity.ok(projectService.getProjectAsAdmin(id));
    }
}
