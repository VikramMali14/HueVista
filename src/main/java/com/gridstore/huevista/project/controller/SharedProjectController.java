package com.gridstore.huevista.project.controller;

import com.gridstore.huevista.project.dto.ProjectResponse;
import com.gridstore.huevista.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/share")
@RequiredArgsConstructor
public class SharedProjectController {

    private final ProjectService projectService;

    /**
     * GET /api/share/{token} — public endpoint for shared project view.
     * No authentication required. Shade codes are hidden; only hex colors shown.
     * Returns 404 if token is invalid or expired.
     */
    @GetMapping("/{token}")
    public ResponseEntity<ProjectResponse> getSharedProject(@PathVariable String token) {
        return ResponseEntity.ok(projectService.getSharedProject(token));
    }
}
