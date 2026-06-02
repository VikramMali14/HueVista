package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.PaintLine;
import com.gridstore.huevista.paint.model.ProductCategory;
import com.gridstore.huevista.paint.model.QualityTier;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.PaintLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.gridstore.huevista.paint.model.ProductCategory.EXTERIOR;
import static com.gridstore.huevista.paint.model.ProductCategory.INTERIOR;
import static com.gridstore.huevista.paint.model.QualityTier.*;

/**
 * Seeds a starter reference catalogue of brands + interior/exterior product lines
 * so the cascading checkboxes have data out of the box. Idempotent (skips lines
 * that already exist) and runs in every profile — it's reference data, and shops
 * can add their own brands/lines on top.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class PaintLineSeeder implements ApplicationRunner {

    private final BrandRepository brandRepository;
    private final PaintLineRepository lineRepository;

    private record Seed(String brand, ProductCategory category, String name, QualityTier tier, String finish) {}

    private static final List<Seed> SEEDS = List.of(
            // Asian Paints
            new Seed("Asian Paints", INTERIOR, "Tractor Emulsion", ECONOMY, "Matt"),
            new Seed("Asian Paints", INTERIOR, "Apcolite Premium Emulsion", PREMIUM, "Sheen"),
            new Seed("Asian Paints", INTERIOR, "Royale Luxury Emulsion", LUXURY, "Soft Sheen"),
            new Seed("Asian Paints", EXTERIOR, "Ace Exterior Emulsion", ECONOMY, "Matt"),
            new Seed("Asian Paints", EXTERIOR, "Apex Weatherproof", PREMIUM, "Matt"),
            new Seed("Asian Paints", EXTERIOR, "Apex Ultima", LUXURY, "Low Sheen"),
            // Berger
            new Seed("Berger", INTERIOR, "Bison Acrylic Emulsion", ECONOMY, "Matt"),
            new Seed("Berger", INTERIOR, "Silk Glamor", PREMIUM, "Sheen"),
            new Seed("Berger", INTERIOR, "Easy Clean", PREMIUM, "Soft Sheen"),
            new Seed("Berger", EXTERIOR, "Walmasta", ECONOMY, "Matt"),
            new Seed("Berger", EXTERIOR, "WeatherCoat Anti Dustt", PREMIUM, "Matt"),
            new Seed("Berger", EXTERIOR, "WeatherCoat Long Life", LUXURY, "Sheen"),
            // Nerolac
            new Seed("Nerolac", INTERIOR, "Beauty Smooth Finish", PREMIUM, "Sheen"),
            new Seed("Nerolac", INTERIOR, "Impressions HD", LUXURY, "Soft Sheen"),
            new Seed("Nerolac", EXTERIOR, "Excel Mica Marble", PREMIUM, "Matt"),
            new Seed("Nerolac", EXTERIOR, "Excel Total", LUXURY, "Sheen"),
            // Dulux
            new Seed("Dulux", INTERIOR, "Promise", ECONOMY, "Matt"),
            new Seed("Dulux", INTERIOR, "SuperClean", PREMIUM, "Sheen"),
            new Seed("Dulux", INTERIOR, "Velvet Touch", LUXURY, "Soft Sheen"),
            new Seed("Dulux", EXTERIOR, "Weathershield", PREMIUM, "Matt"),
            new Seed("Dulux", EXTERIOR, "Weathershield Max", LUXURY, "Sheen"),
            // Baxter
            new Seed("Baxter", INTERIOR, "InteriorPro", PREMIUM, "Sheen"),
            new Seed("Baxter", EXTERIOR, "Tuffshield", PREMIUM, "Matt"),
            new Seed("Baxter", EXTERIOR, "Duratech", PREMIUM, "Sheen"),
            // Esdee
            new Seed("Esdee", INTERIOR, "Interior Emulsion", ECONOMY, "Matt"),
            new Seed("Esdee", EXTERIOR, "Exterior Emulsion", PREMIUM, "Matt"),
            new Seed("Esdee", EXTERIOR, "Synthetic Enamel", PREMIUM, "Gloss")
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int created = 0;
        for (Seed s : SEEDS) {
            String slug = PaintCatalogueService.slugify(s.brand());
            Brand brand = brandRepository.findBySlug(slug)
                    .orElseGet(() -> brandRepository.save(Brand.builder().name(s.brand()).slug(slug).build()));
            boolean exists = lineRepository
                    .findFirstByBrandIdAndCategoryAndNameIgnoreCase(brand.getId(), s.category(), s.name())
                    .isPresent();
            if (!exists) {
                lineRepository.save(PaintLine.builder()
                        .brand(brand).category(s.category()).name(s.name())
                        .qualityTier(s.tier()).defaultFinish(s.finish()).build());
                created++;
            }
        }
        if (created > 0) log.info("[paint-seed] added {} product lines across {} seed entries", created, SEEDS.size());
    }
}
