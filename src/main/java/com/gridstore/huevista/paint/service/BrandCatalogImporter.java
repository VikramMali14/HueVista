package com.gridstore.huevista.paint.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.util.ColorMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Imports a brand's shade catalogue from bundled JSON files in the simple
 * {@code {name, code, hex, category}} format (Berger / Dulux / Nerolac / Nippon),
 * as opposed to the richer Asian Paints API shape handled by {@link ShadeSeederService}.
 *
 * <p>No AI enrichment — only hex, RGB and LRV are derived locally. The import is
 * idempotent and safe to re-run: shades already present (by brand + code) are skipped,
 * and duplicate codes within or across the supplied files are de-duplicated, so the
 * {@code (brand_id, shade_code)} unique constraint is never violated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandCatalogImporter {

    private final BrandRepository brandRepository;
    private final ShadeRepository shadeRepository;
    private final ObjectMapper objectMapper;

    private static final int SAVE_CHUNK = 500;

    /**
     * Imports one brand from one or more classpath resources. Each resource is either a
     * top-level JSON array or a {@code {"shades":[...]}} object. When multiple files are
     * given they are processed in order, so a categorised file listed first wins the
     * {@code shadeFamily} over a later uncategorised file for the same code.
     *
     * @return the number of newly inserted shades
     */
    @Transactional
    public int importBrand(String brandName, String brandSlug, List<String> resourcePaths) {
        Brand brand = brandRepository.findBySlug(brandSlug)
                .orElseGet(() -> brandRepository.save(
                        Brand.builder().name(brandName).slug(brandSlug).build()));

        // Codes already persisted for this brand, plus everything queued this run —
        // one membership set drives both DB-level and in-file de-duplication.
        Set<String> seen = new HashSet<>(shadeRepository.findShadeCodesByBrandId(brand.getId()));

        List<Shade> pending = new ArrayList<>();
        int inserted = 0, skippedDuplicate = 0, skippedBadHex = 0, skippedMalformed = 0;

        for (String path : resourcePaths) {
            for (JsonNode row : readShadeArray(path)) {
                String code = text(row, "code");
                String name = text(row, "name");
                String hex = text(row, "hex");
                String category = text(row, "category"); // may be absent (e.g. Nerolac colours)

                if (code == null || name == null || hex == null) {
                    skippedMalformed++;
                    continue;
                }
                if (!ColorMath.isValidHex(hex)) {
                    skippedBadHex++;
                    log.warn("[catalog-import] {} skipping '{}' ({}) — invalid hex '{}'", brandSlug, name, code, hex);
                    continue;
                }
                if (!seen.add(code)) {
                    skippedDuplicate++;
                    continue;
                }

                String normHex = ColorMath.normalizeHex(hex);
                int[] rgb = ColorMath.hexToRgb(normHex);
                BigDecimal lrv = ColorMath.calculateLrv(rgb[0], rgb[1], rgb[2]);

                pending.add(Shade.builder()
                        .brand(brand)
                        .shadeCode(code)
                        .name(name)
                        .hexCode(normHex)
                        .shadeFamily(category)
                        .lrv(lrv)
                        .rgbR(rgb[0])
                        .rgbG(rgb[1])
                        .rgbB(rgb[2])
                        .build());

                if (pending.size() >= SAVE_CHUNK) {
                    shadeRepository.saveAll(pending);
                    inserted += pending.size();
                    pending.clear();
                }
            }
        }
        if (!pending.isEmpty()) {
            shadeRepository.saveAll(pending);
            inserted += pending.size();
        }

        if (inserted > 0 || skippedBadHex > 0 || skippedMalformed > 0) {
            log.info("[catalog-import] {} — inserted {} (skipped {} existing/duplicate, {} bad hex, {} malformed)",
                    brandSlug, inserted, skippedDuplicate, skippedBadHex, skippedMalformed);
        }
        return inserted;
    }

    /** Reads a resource as a list of shade nodes, tolerating both array and {"shades":[...]} shapes. */
    private List<JsonNode> readShadeArray(String resourcePath) {
        try (InputStream is = new ClassPathResource(resourcePath).getInputStream()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode array = root.isArray() ? root : root.get("shades");
            if (array == null || !array.isArray()) {
                log.error("[catalog-import] {} has no shade array — skipping", resourcePath);
                return List.of();
            }
            List<JsonNode> rows = new ArrayList<>(array.size());
            array.forEach(rows::add);
            return rows;
        } catch (Exception e) {
            log.error("[catalog-import] failed to read {}: {}", resourcePath, e.getMessage());
            return List.of();
        }
    }

    /** Trimmed text value for a field, or {@code null} if absent/null/blank. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
