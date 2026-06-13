package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.paint.dto.AsianPaintsShadeDto;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.util.ColorMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadeSeederService {

    private final BrandRepository brandRepository;
    private final ShadeRepository shadeRepository;
    private final ShadeEnrichmentService enrichmentService;

    /**
     * Seeds shades for a brand from the Asian Paints API response format.
     * Idempotent — skips shades that already exist by (brandId, shadeCode).
     * Returns the number of newly inserted shades.
     */
    @Transactional
    public int seed(String brandName, String brandSlug, List<AsianPaintsShadeDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            log.warn("No shades provided to seed for brand '{}'", brandName);
            return 0;
        }

        Brand brand = brandRepository.findBySlug(brandSlug)
                .orElseGet(() -> brandRepository.save(
                        Brand.builder().name(brandName).slug(brandSlug).build()
                ));

        // Skip shades already in DB (safe to re-run)
        List<AsianPaintsShadeDto> toSeed = dtos.stream()
                .filter(dto -> dto.getEntityCode() != null
                        && !shadeRepository.existsByBrandIdAndShadeCode(brand.getId(), dto.getEntityCode()))
                .toList();

        if (toSeed.isEmpty()) {
            log.info("All {} shades already seeded for brand '{}'", dtos.size(), brandName);
            return 0;
        }

        log.info("Seeding {} new shades for brand '{}' with AI enrichment...", toSeed.size(), brandName);

        // Build inputs for Claude enrichment
        List<ShadeEnrichmentService.ShadeInput> inputs = toSeed.stream()
                .map(dto -> new ShadeEnrichmentService.ShadeInput(
                        dto.getEntityName(),
                        dto.getShadeHexCode(),
                        dto.getShadeFamily(),
                        extractFirst(dto.getFilterTitle(), "color temperature"),
                        extractFirst(dto.getFilterTitle(), "tonality")
                ))
                .toList();

        List<ShadeEnrichmentService.EnrichmentResult> enrichments = enrichmentService.enrichBatch(inputs);

        List<Shade> shades = new ArrayList<>();
        for (int i = 0; i < toSeed.size(); i++) {
            AsianPaintsShadeDto dto = toSeed.get(i);
            ShadeEnrichmentService.EnrichmentResult enrichment = enrichments.get(i);

            int[] rgb = ColorMath.hexToRgb(dto.getShadeHexCode());
            BigDecimal lrv = ColorMath.calculateLrv(rgb[0], rgb[1], rgb[2]);

            shades.add(Shade.builder()
                    .brand(brand)
                    .shadeCode(dto.getEntityCode())
                    .name(dto.getEntityName())
                    .hexCode(ColorMath.normalizeHex(dto.getShadeHexCode()))
                    .shadeFamily(dto.getShadeFamily())
                    .featureTag(nullIfBlank(dto.getFeatureTag()))
                    .popularity(parsePopularity(dto.getPopularity()))
                    .pageUrl(dto.getPageUrl())
                    .colorTemperature(extractFirst(dto.getFilterTitle(), "color temperature"))
                    .tonality(extractFirst(dto.getFilterTitle(), "tonality"))
                    .suitableRooms(extractList(dto.getFilterTitle(), "room"))
                    .lrv(lrv)
                    .rgbR(rgb[0])
                    .rgbG(rgb[1])
                    .rgbB(rgb[2])
                    .styleTags(enrichment.styleTags())
                    .moodDescriptors(enrichment.moodDescriptors())
                    .finishRecommendations(enrichment.finishRecommendations())
                    .aiDescription(enrichment.aiDescription())
                    .build());
        }

        shadeRepository.saveAll(shades);
        log.info("Seeding complete — {} shades saved for brand '{}'", shades.size(), brandName);
        return shades.size();
    }

    // ── AP API filterTitle helpers ────────────────────────────────────────────

    private String extractFirst(Map<String, List<String>> map, String key) {
        if (map == null) return null;
        List<String> vals = map.get(key);
        return (vals != null && !vals.isEmpty()) ? vals.get(0) : null;
    }

    private List<String> extractList(Map<String, List<String>> map, String key) {
        if (map == null) return List.of();
        return map.getOrDefault(key, List.of());
    }

    private Integer parsePopularity(String val) {
        if (val == null || val.isBlank()) return null;
        try { return Integer.parseInt(val.trim()); } catch (NumberFormatException e) { return null; }
    }

    private String nullIfBlank(String val) {
        return (val == null || val.isBlank()) ? null : val;
    }
}
