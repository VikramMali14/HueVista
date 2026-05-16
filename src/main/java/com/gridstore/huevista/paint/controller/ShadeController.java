package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.AsianPaintsApiResponse;
import com.gridstore.huevista.paint.dto.ShadeResponse;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.service.ShadeSeederService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Paint Catalog", description = "Browse and filter paint shades by brand, family, and color properties")
public class ShadeController {

    private final ShadeRepository shadeRepository;
    private final ShadeSeederService seederService;

    @Operation(
            summary = "List shades with filters",
            description = """
                    Returns shades across all brands or filtered by any combination of:
                    - `brand` — brand slug, e.g. `asian-paints`
                    - `family` — shade family, e.g. `off whites`, `blues`
                    - `temperature` — `cool`, `warm`, or `neutral`
                    - `tonality` — `light`, `medium`, or `dark`
                    - `search` — matches shade name (partial) or exact shade code

                    Results are sorted by popularity ascending.
                    """
    )
    @ApiResponse(responseCode = "200", description = "Shade list")
    @SecurityRequirements
    @Cacheable(value = "shades", key = "#brand + ':' + #family + ':' + #temperature + ':' + #tonality + ':' + #search")
    @GetMapping("/api/shades")
    public ResponseEntity<List<ShadeResponse>> getShades(
            @Parameter(description = "Brand slug, e.g. asian-paints") @RequestParam(required = false) String brand,
            @Parameter(description = "Shade family, e.g. off whites") @RequestParam(required = false) String family,
            @Parameter(description = "cool / warm / neutral") @RequestParam(required = false) String temperature,
            @Parameter(description = "light / medium / dark") @RequestParam(required = false) String tonality,
            @Parameter(description = "Name or exact shade code") @RequestParam(required = false) String search
    ) {
        List<ShadeResponse> shades = shadeRepository
                .findWithFilters(brand, family, temperature, tonality, search)
                .stream()
                .map(ShadeResponse::from)
                .toList();
        return ResponseEntity.ok(shades);
    }

    @Operation(summary = "List shades by brand", description = "Returns all shades for a brand slug ordered by popularity.")
    @ApiResponse(responseCode = "200", description = "Shade list for the brand")
    @SecurityRequirements
    @GetMapping("/api/shades/{brand}")
    public ResponseEntity<List<ShadeResponse>> getShadesByBrand(
            @Parameter(description = "Brand slug, e.g. asian-paints") @PathVariable String brand
    ) {
        List<ShadeResponse> shades = shadeRepository
                .findByBrandSlugOrderByPopularityAsc(brand)
                .stream()
                .map(ShadeResponse::from)
                .toList();
        return ResponseEntity.ok(shades);
    }

    @Operation(summary = "List shade families", description = "Returns distinct shade family names for a brand — useful for building filter UI dropdowns.")
    @ApiResponse(responseCode = "200", description = "List of family names")
    @SecurityRequirements
    @Cacheable(value = "shade-families", key = "#brand")
    @GetMapping("/api/shades/{brand}/families")
    public ResponseEntity<List<String>> getShadesFamilies(
            @Parameter(description = "Brand slug, e.g. asian-paints") @PathVariable String brand
    ) {
        return ResponseEntity.ok(shadeRepository.findDistinctFamiliesByBrandSlug(brand));
    }

    @Operation(summary = "Get a single shade", description = "Returns full detail for one shade by brand slug and shade code.")
    @ApiResponse(responseCode = "200", description = "Shade detail")
    @ApiResponse(responseCode = "404", description = "Shade not found")
    @SecurityRequirements
    @Cacheable(value = "shade-detail", key = "#brand + ':' + #code")
    @GetMapping("/api/shades/{brand}/{code}")
    public ResponseEntity<ShadeResponse> getShade(
            @Parameter(description = "Brand slug, e.g. asian-paints") @PathVariable String brand,
            @Parameter(description = "Shade code, e.g. 9436") @PathVariable String code
    ) {
        return shadeRepository.findByBrandSlugAndShadeCode(brand, code)
                .map(ShadeResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Seed Asian Paints catalog (admin)",
            description = """
                    Accepts the raw Asian Paints API JSON response, enriches each shade with
                    Claude AI (style tags, mood descriptors, finish recommendations, description),
                    and bulk-inserts into the database.

                    **Idempotent** — re-running only inserts shades not already present.

                    Expected body format:
                    ```json
                    { "success": true, "shade": [ { "entityCode": "9436", ... } ] }
                    ```
                    """
    )
    @ApiResponse(responseCode = "200", description = "Seeding result with count of inserted shades")
    @PostMapping("/api/admin/paint/seed/asian-paints")
    public ResponseEntity<Map<String, Object>> seedAsianPaints(@RequestBody AsianPaintsApiResponse body) {
        int seeded = seederService.seed("Asian Paints", "asian-paints", body.getShade());
        return ResponseEntity.ok(Map.of(
                "seeded", seeded,
                "message", seeded > 0
                        ? "Successfully seeded " + seeded + " shades"
                        : "No new shades to seed -- all already exist"
        ));
    }
}
