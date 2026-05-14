package com.gridstore.huevista.project.controller;

import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
