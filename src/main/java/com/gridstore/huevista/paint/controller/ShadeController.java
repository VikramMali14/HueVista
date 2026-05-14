package com.gridstore.huevista.paint.controller;

import com.gridstore.huevista.paint.dto.AsianPaintsApiResponse;
import com.gridstore.huevista.paint.dto.ShadeResponse;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.service.ShadeSeederService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ShadeController {

    private final ShadeRepository shadeRepository;
    private final ShadeSeederService seederService;

    /**
     * GET /api/shades
     * Optional filters: brand, family, temperature, tonality, search (name or shade code)
     */
    @GetMapping("/api/shades")
    public ResponseEntity<List<ShadeResponse>> getShades(
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String family,
            @RequestParam(required = false) String temperature,
            @RequestParam(required = false) String tonality,
            @RequestParam(required = false) String search
    ) {
        List<ShadeResponse> shades = shadeRepository
                .findWithFilters(brand, family, temperature, tonality, search)
                .stream()
                .map(ShadeResponse::from)
                .toList();
        return ResponseEntity.ok(shades);
    }

    /**
     * GET /api/shades/{brand}
     * Returns all shades for a brand slug, ordered by popularity.
     */
    @GetMapping("/api/shades/{brand}")
    public ResponseEntity<List<ShadeResponse>> getShadesByBrand(@PathVariable String brand) {
        List<ShadeResponse> shades = shadeRepository
                .findByBrandSlugOrderByPopularityAsc(brand)
                .stream()
                .map(ShadeResponse::from)
                .toList();
        return ResponseEntity.ok(shades);
    }

    /**
     * GET /api/shades/{brand}/families
     * Returns distinct shade family names for a brand (useful for filter UI).
     */
    @GetMapping("/api/shades/{brand}/families")
    public ResponseEntity<List<String>> getShadesFamilies(@PathVariable String brand) {
        return ResponseEntity.ok(shadeRepository.findDistinctFamiliesByBrandSlug(brand));
    }

    /**
     * GET /api/shades/{brand}/{code}
     * Returns a single shade by brand slug + shade code.
     */
    @GetMapping("/api/shades/{brand}/{code}")
    public ResponseEntity<ShadeResponse> getShade(
            @PathVariable String brand,
            @PathVariable String code
    ) {
        return shadeRepository.findByBrandSlugAndShadeCode(brand, code)
                .map(ShadeResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/admin/paint/seed/asian-paints
     * Accepts the raw Asian Paints API JSON body, enriches with AI, and seeds the DB.
     * Idempotent — safe to call multiple times; only inserts new shades.
     * Requires authentication.
     */
    @PostMapping("/api/admin/paint/seed/asian-paints")
    public ResponseEntity<Map<String, Object>> seedAsianPaints(
            @RequestBody AsianPaintsApiResponse body
    ) {
        int seeded = seederService.seed("Asian Paints", "asian-paints", body.getShade());
        return ResponseEntity.ok(Map.of(
                "seeded", seeded,
                "message", seeded > 0
                        ? "Successfully seeded " + seeded + " shades"
                        : "No new shades to seed — all already exist"
        ));
    }
}
