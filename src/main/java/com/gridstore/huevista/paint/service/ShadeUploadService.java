package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.paint.dto.ShadeUploadItem;
import com.gridstore.huevista.paint.dto.ShadeUploadResponse;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.paint.util.ColorMath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bulk-imports a company's shades from a plain JSON array (the public upload page).
 *
 * <p>Always computes the pure-maths fields (RGB split + LRV) from the hex. When
 * {@code enrich} is set, it also runs each new shade through Claude — the same
 * {@link ShadeEnrichmentService} the seeder uses — to fill in style tags, mood
 * descriptors, finish recommendations and a one-line description. Enrichment degrades
 * gracefully: if Claude is unavailable those fields are just left empty. Idempotent:
 * shades already present for the company (by shade code) are skipped, as are duplicate
 * codes within the same file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadeUploadService {

    /** Guard-rail so a single request can't try to insert an unbounded batch. */
    static final int MAX_SHADES_PER_UPLOAD = 10_000;

    private final BrandRepository brandRepository;
    private final ShadeRepository shadeRepository;
    private final ShadeEnrichmentService enrichmentService;

    @Transactional
    public ShadeUploadResponse upload(String brandSlug, String brandName,
                                      List<ShadeUploadItem> shades, boolean enrich) {
        if (shades == null || shades.isEmpty()) {
            throw new IllegalArgumentException("Upload a non-empty JSON array of shades.");
        }
        if (shades.size() > MAX_SHADES_PER_UPLOAD) {
            throw new IllegalArgumentException(
                    "Too many shades in one upload (" + shades.size() + "); the limit is "
                            + MAX_SHADES_PER_UPLOAD + ". Split the file and upload in batches.");
        }

        Brand brand = resolveBrand(brandSlug, brandName);

        int total = shades.size();
        int skipped = 0;
        Set<String> seenCodes = new HashSet<>();
        List<Shade> toSave = new ArrayList<>();

        for (int i = 0; i < shades.size(); i++) {
            ShadeUploadItem item = shades.get(i);
            int row = i + 1;

            String code = StringUtils.trimAllWhitespace(nullToEmpty(item.getCode()));
            String name = trimToNull(item.getName());
            String hex = trimToNull(item.getHex());

            if (code.isEmpty()) {
                throw new IllegalArgumentException("Row " + row + ": \"code\" is required.");
            }
            if (name == null) {
                throw new IllegalArgumentException("Row " + row + " (" + code + "): \"name\" is required.");
            }
            if (!ColorMath.isValidHex(hex)) {
                throw new IllegalArgumentException("Row " + row + " (" + code
                        + "): \"hex\" must be a 6-digit colour like #F3EDE8.");
            }

            // Duplicate code inside the same file, or already in the catalogue for this brand.
            if (!seenCodes.add(code) || shadeRepository.existsByBrandIdAndShadeCode(brand.getId(), code)) {
                skipped++;
                continue;
            }

            String normHex = ColorMath.normalizeHex(hex);
            int[] rgb = ColorMath.hexToRgb(normHex);
            BigDecimal lrv = ColorMath.calculateLrv(rgb[0], rgb[1], rgb[2]);

            toSave.add(Shade.builder()
                    .brand(brand)
                    .shadeCode(code)
                    .name(name)
                    .hexCode(normHex)
                    .shadeFamily(trimToNull(item.getFamily()))
                    .featureTag(trimToNull(item.getFeatureTag()))
                    .popularity(item.getPopularity())
                    .pageUrl(trimToNull(item.getPageUrl()))
                    .colorTemperature(trimToNull(item.getColorTemperature()))
                    .tonality(trimToNull(item.getTonality()))
                    .suitableRooms(cleanList(item.getSuitableRooms()))
                    .lrv(lrv)
                    .rgbR(rgb[0])
                    .rgbG(rgb[1])
                    .rgbB(rgb[2])
                    .build());
        }

        if (enrich && !toSave.isEmpty()) {
            applyAiEnrichment(toSave);
        }

        if (!toSave.isEmpty()) {
            shadeRepository.saveAll(toSave);
        }
        log.info("Shade upload: brand='{}' total={} inserted={} skipped={} enriched={}",
                brand.getName(), total, toSave.size(), skipped, enrich);

        return ShadeUploadResponse.builder()
                .brand(brand.getName())
                .slug(brand.getSlug())
                .total(total)
                .inserted(toSave.size())
                .skipped(skipped)
                .build();
    }

    /**
     * Find the company by slug (existing dropdown pick) or create it from a typed name.
     * Deduped by slug; safe against a concurrent create of the same company.
     */
    private Brand resolveBrand(String brandSlug, String brandName) {
        String slug = StringUtils.hasText(brandSlug) ? slugify(brandSlug)
                : StringUtils.hasText(brandName) ? slugify(brandName) : null;
        if (slug == null) {
            throw new IllegalArgumentException("Select an existing company or enter a new company name.");
        }
        return brandRepository.findBySlug(slug).orElseGet(() -> {
            String name = StringUtils.hasText(brandName) ? brandName.trim() : prettify(slug);
            try {
                return brandRepository.save(Brand.builder().name(name).slug(slug).build());
            } catch (DataIntegrityViolationException race) {
                // Lost a race creating the same company — use the winner's row.
                return brandRepository.findBySlug(slug).orElseThrow(() -> race);
            }
        });
    }

    /**
     * Runs the newly-inserted shades through Claude and copies the style tags, mood
     * descriptors, finish recommendations and description back onto each shade (matched by
     * position — {@link ShadeEnrichmentService#enrichBatch} returns one result per input in
     * order). Best-effort: a failed batch just leaves those fields empty, so the upload
     * still succeeds.
     */
    private void applyAiEnrichment(List<Shade> shades) {
        List<ShadeEnrichmentService.ShadeInput> inputs = shades.stream()
                .map(s -> new ShadeEnrichmentService.ShadeInput(
                        s.getName(),
                        s.getHexCode(),
                        s.getShadeFamily(),
                        s.getColorTemperature(),
                        s.getTonality()))
                .toList();

        List<ShadeEnrichmentService.EnrichmentResult> results = enrichmentService.enrichBatch(inputs);

        for (int i = 0; i < shades.size() && i < results.size(); i++) {
            ShadeEnrichmentService.EnrichmentResult r = results.get(i);
            Shade s = shades.get(i);
            s.setStyleTags(emptyToNull(r.styleTags()));
            s.setMoodDescriptors(emptyToNull(r.moodDescriptors()));
            s.setFinishRecommendations(emptyToNull(r.finishRecommendations()));
            s.setAiDescription(trimToNull(r.aiDescription()));
        }
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private static List<String> emptyToNull(List<String> in) {
        return (in == null || in.isEmpty()) ? null : in;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static List<String> cleanList(List<String> in) {
        if (in == null) return null;
        List<String> out = in.stream().map(ShadeUploadService::trimToNull).filter(java.util.Objects::nonNull).toList();
        return out.isEmpty() ? null : out;
    }

    static String slugify(String name) {
        String s = name == null ? "" : name.toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isBlank() ? "brand" : s;
    }

    private static String prettify(String slug) {
        String[] parts = slug.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? "Brand" : sb.toString();
    }
}
