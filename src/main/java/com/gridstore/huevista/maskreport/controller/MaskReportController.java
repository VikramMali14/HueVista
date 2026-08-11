package com.gridstore.huevista.maskreport.controller;

import com.gridstore.huevista.account.model.AppFeature;
import com.gridstore.huevista.account.security.RequiresFeature;
import com.gridstore.huevista.maskreport.dto.CreateMaskReportRequest;
import com.gridstore.huevista.maskreport.dto.MaskReportResponse;
import com.gridstore.huevista.maskreport.service.MaskReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** The studio's "report a problem" button, for a signed-in owner. */
@RestController
@RequestMapping("/api/projects/{projectId}/mask-reports")
@RequiredArgsConstructor
@Tag(name = "Mask reports", description = "Report an AI run that came out wrong")
@RequiresFeature(AppFeature.STUDIO)
public class MaskReportController {

    private final MaskReportService maskReportService;

    @Operation(summary = "Report a bad AI run on my project",
            description = """
                    Raised from the studio once a run has finished — the walls landed in the
                    wrong places, or the clean-up damaged the photo. Goes straight to the
                    admin queue along with a snapshot of what the run produced.

                    Re-reporting the same project while an earlier report is still open
                    UPDATES that report rather than filing a second one, so a user who tries
                    again after a re-run doesn't fill the queue with duplicates.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Report filed"),
            @ApiResponse(responseCode = "400", description = "No issue was ticked"),
            @ApiResponse(responseCode = "404", description = "Project not found or not owned by user")
    })
    @PostMapping
    public ResponseEntity<MaskReportResponse> report(
            @PathVariable String projectId,
            @Valid @RequestBody CreateMaskReportRequest request,
            Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(maskReportService.report(auth.getName(), projectId, request));
    }
}
