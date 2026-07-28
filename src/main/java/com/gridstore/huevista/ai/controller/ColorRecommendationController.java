package com.gridstore.huevista.ai.controller;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.security.RequiresFeature;
import com.gridstore.huevista.ai.dto.RecommendationResponse;
import com.gridstore.huevista.ai.service.ColorRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Tag(name = "AI Color Recommendations", description = "Claude-powered color palette suggestions for a project")
// Palette suggestions for an open project — this is the Studio's "AI Suggest" tab, not
// the Colour finder page (which matches colours in the browser against the catalogue it
// was served, and has no endpoint of its own to gate).
@RequiresFeature(AppFeature.STUDIO)
public class ColorRecommendationController {

    private final ColorRecommendationService recommendationService;

    @Operation(
        summary = "Get AI color recommendations",
        description = "Analyzes the project image with Claude Vision and returns 3 paint color palettes, " +
                      "each with a primary, accent, and trim color matched to the nearest real shade in the catalog. " +
                      "Consumes one AI generation from the user's subscription quota.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recommendations returned"),
        @ApiResponse(responseCode = "402", description = "No active subscription or AI generation limit reached"),
        @ApiResponse(responseCode = "404", description = "Project not found")
    })
    @PostMapping("/{projectId}/recommendations")
    public ResponseEntity<RecommendationResponse> getRecommendations(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String projectId) {
        return ResponseEntity.ok(
                recommendationService.getRecommendations(userDetails.getUsername(), projectId));
    }
}
