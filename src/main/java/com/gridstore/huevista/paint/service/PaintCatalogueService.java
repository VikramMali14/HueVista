package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.paint.dto.BrandResponse;
import com.gridstore.huevista.paint.dto.CreateLineRequest;
import com.gridstore.huevista.paint.dto.LineResponse;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.PaintLine;
import com.gridstore.huevista.paint.model.ProductCategory;
import com.gridstore.huevista.paint.model.QualityTier;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.PaintLineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The shared reference catalogue of brands → interior/exterior product lines that
 * drives the cascading checkboxes. Seeded with common brands; shops can add a
 * brand or line that's missing (kept global, deduped by slug / name).
 */
@Service
@RequiredArgsConstructor
public class PaintCatalogueService {

    private final BrandRepository brandRepository;
    private final PaintLineRepository lineRepository;

    @Transactional(readOnly = true)
    public List<BrandResponse> listBrands() {
        return brandRepository.findAllByOrderByNameAsc().stream().map(BrandResponse::from).toList();
    }

    @Transactional
    public BrandResponse addBrand(String name) {
        String slug = slugify(name);
        Brand brand = brandRepository.findBySlug(slug).orElse(null);
        if (brand == null) {
            try {
                brand = brandRepository.save(Brand.builder().name(name.trim()).slug(slug).build());
            } catch (DataIntegrityViolationException race) {
                // A concurrent request created it first — return the existing one.
                brand = brandRepository.findBySlug(slug)
                        .orElseThrow(() -> race);
            }
        }
        return BrandResponse.from(brand);
    }

    @Transactional(readOnly = true)
    public List<LineResponse> listLines(Long brandId, ProductCategory category) {
        return lineRepository.findByBrandIdAndCategoryOrderByQualityTierAscNameAsc(brandId, category)
                .stream().map(LineResponse::from).toList();
    }

    @Transactional
    public LineResponse addLine(Long brandId, CreateLineRequest req) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new ResourceNotFoundException("Brand not found: " + brandId));
        String name = req.getName().trim();
        return lineRepository
                .findFirstByBrandIdAndCategoryAndNameIgnoreCase(brandId, req.getCategory(), name)
                .map(LineResponse::from)
                .orElseGet(() -> {
                    try {
                        return LineResponse.from(lineRepository.save(PaintLine.builder()
                                .brand(brand)
                                .category(req.getCategory())
                                .name(name)
                                .qualityTier(req.getQualityTier() != null ? req.getQualityTier() : QualityTier.PREMIUM)
                                .defaultFinish(req.getDefaultFinish())
                                .build()));
                    } catch (DataIntegrityViolationException race) {
                        // Lost a race on the unique (brand, category, name) — return the winner.
                        return lineRepository
                                .findFirstByBrandIdAndCategoryAndNameIgnoreCase(brandId, req.getCategory(), name)
                                .map(LineResponse::from)
                                .orElseThrow(() -> race);
                    }
                });
    }

    static String slugify(String name) {
        String s = name == null ? "" : name.toLowerCase().trim()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isBlank() ? "brand" : s;
    }
}
