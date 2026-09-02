package com.gridstore.huevista.admin;

import com.gridstore.huevista.project.dto.AdminProjectRow;
import com.gridstore.huevista.project.dto.MaskRegistrationRequest;
import com.gridstore.huevista.project.dto.MaskRegistrationResponse;
import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.service.MaskRegistrationService;
import com.gridstore.huevista.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * <p>Reading is the whole of it bar one thing: the mask registration. Diagnosing a run
 * that put the walls in the wrong places and then being unable to correct it is half a
 * tool, and the correction is not a studio action — it is a whole-frame measurement made
 * against somebody else's room, which is exactly what this controller already exists to
 * reach. So {@code POST /{id}/mask-registration} writes, and nothing else here does. It
 * moves the model's drawing without reshaping it, leaves hand-drawn walls alone, and
 * spends no credit; the detail response still comes back flagged read-only so the studio
 * keeps its own save paths shut. ROLE_ADMIN only.
 */
@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin projects", description = "Every room, for mask diagnostics — ROLE_ADMIN only")
public class AdminProjectController {

    private final ProjectService projectService;
    private final MaskRegistrationService maskRegistrationService;

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

    @Operation(summary = "Read the hand-made mask registration", description = """
            Where an admin last decided this room's masks belong on the canvas, so the
            align bench can re-open that placement and adjust it rather than start over.

            204 when nobody has hand-placed one, which is nearly every room and says the
            automatic fit is what shipped.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stored registration"),
            @ApiResponse(responseCode = "204", description = "Nobody has hand-registered this room"),
            @ApiResponse(responseCode = "404", description = "No such project")
    })
    @GetMapping("/{id}/mask-registration")
    public ResponseEntity<MaskRegistrationRequest> getMaskRegistration(@PathVariable String id) {
        MaskRegistrationRequest current = maskRegistrationService.current(id);
        return current == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(current);
    }

    @Operation(summary = "Put this room's masks where the admin says they belong", description = """
            Re-splits the model's stored colour-coded mask and re-lands each detected
            surface at the given registration — a whole-frame scale and offset, plus an
            optional lattice of per-position nudges for a facade that drifted by different
            amounts in different parts of the frame.

            This MOVES the model's drawing; it does not redraw it. Region rows, ids, labels
            and applied colours all survive, so a room that has been painted, planned or put
            on a colour board keeps every one of those and only the mask underneath changes.
            Hand-drawn (MANUAL) walls are left alone: they were marked against the canvas
            directly and are already registered to it.

            The first re-registration files each region's previous mask as its original, so
            "Restore original" still has somewhere to go back to.

            Costs nothing — no model runs and no credit is spent. Needs the room's raw
            colour-coded mask, which rooms segmented before raw-mask capture shipped, and
            hand-drawn-only rooms, do not have.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Masks re-landed; says which moved"),
            @ApiResponse(responseCode = "400", description = "The registration is outside what can be resampled, or folds"),
            @ApiResponse(responseCode = "409", description = "Nothing to re-register, or it would push a wall off the canvas"),
            @ApiResponse(responseCode = "404", description = "No such project")
    })
    @PostMapping("/{id}/mask-registration")
    public ResponseEntity<MaskRegistrationResponse> applyMaskRegistration(
            @PathVariable String id,
            @Valid @RequestBody MaskRegistrationRequest request) {
        return ResponseEntity.ok(maskRegistrationService.apply(id, request));
    }
}
