package com.gridstore.huevista.admin;

import com.gridstore.huevista.project.dto.MaskLabRequest;
import com.gridstore.huevista.project.dto.MaskLabResponse;
import com.gridstore.huevista.project.service.MaskLabService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The mask lab: run one photograph through each way of producing a mask and see
 * what comes back.
 *
 * <p>Nothing here belongs to a project. A run writes no region, spends no
 * credit, and cannot be reached from the studio — it takes an image an admin
 * uploaded, asks one approach for a mask, and hands back images. That
 * separation is the point: the comparison has to be possible on a real facade
 * before anything in the pipeline changes, and it must not be possible to
 * change a customer's room by experimenting.
 *
 * <p>ROLE_ADMIN only, and one approach ({@code CUSTOM_REPLICATE}) will run any
 * model an admin names — see {@link MaskLabService} for why that is not
 * allow-listed and what bounds it instead.
 */
@RestController
@RequestMapping("/api/admin/mask-lab")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin · mask lab", description = "Compare ways of producing a mask — ROLE_ADMIN only")
public class AdminMaskLabController {

    private final MaskLabService maskLabService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "Run one approach against one image", description = """
            Multipart: the cleaned image under `file`, and the run's settings as a JSON
            string under `request`.

            The settings are a union — each approach reads what means something to it:

            - GENERATIVE: `model` (optional, checked against the model catalogue), `scene`
              (INDOOR forces an accent surface). What the pipeline ships; redraws the
              photo, so its blocks land a little off.
            - PAINTED_SURFACE: `tolerance`, `minBlobShare`. Reads the surfaces the clean
              already repainted, out of the pixels. No model, no cost, no drift — and no
              way to tell wall from trim, because the clean paints both the same white.
            - SAM_POINTS: `points` (normalised 0–1) and `pointLabels` (1 include,
              0 exclude). Exact, unnamed, one surface per run.
            - CUSTOM_REPLICATE: `model` as owner/name, and `inputTemplate` — the model's
              input body as JSON with `{{image}}` where the image URL goes. For trying a
              semantic segmenter, a facade parser or a text-grounded model without a
              deploy per model.

            Runs are synchronous and wait up to 60s for a model. Images come back as URLs.
            """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "What the approach produced"),
            @ApiResponse(responseCode = "400", description = "Bad image, bad settings, or a model outside the catalogue"),
            @ApiResponse(responseCode = "409", description = "The approach ran and produced no mask")
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MaskLabResponse> run(
            @Parameter(description = "The cleaned image to run against") @RequestParam("file") MultipartFile file,
            @Parameter(description = "MaskLabRequest as a JSON string") @RequestParam("request") String request) {
        MaskLabRequest parsed;
        try {
            parsed = objectMapper.readValue(request, MaskLabRequest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the run settings: " + e.getMessage());
        }
        return ResponseEntity.ok(maskLabService.run(file, parsed));
    }
}
