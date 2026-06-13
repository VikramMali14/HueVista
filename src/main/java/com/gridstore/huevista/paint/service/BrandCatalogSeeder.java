package com.gridstore.huevista.paint.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Loads the bundled Berger / Dulux / Nerolac / Nippon shade catalogues into the
 * {@code shades} table on startup (no AI enrichment). Idempotent — already-present
 * shades are skipped — so it is safe to run on every boot. Asian Paints is seeded
 * separately through the admin endpoint (with Claude enrichment).
 *
 * <p>Disable with {@code app.catalog.auto-seed=false} (tests do this).
 */
@Slf4j
@Component
@Order(3) // after PaintLineSeeder (@Order 2), which creates the brand rows
@ConditionalOnProperty(value = "app.catalog.auto-seed", matchIfMissing = true)
@RequiredArgsConstructor
public class BrandCatalogSeeder implements ApplicationRunner {

    private final BrandCatalogImporter importer;

    private record Catalog(String name, String slug, List<String> resources) {}

    private static final String DIR = "seed/brands/";

    private static final List<Catalog> CATALOGS = List.of(
            new Catalog("Berger", "berger", List.of(DIR + "berger.json")),
            new Catalog("Dulux", "dulux", List.of(DIR + "dulux.json")),
            // Categorised file first so its shadeFamily wins over the uncategorised colours file.
            new Catalog("Nerolac", "nerolac", List.of(DIR + "nerolac-paints.json", DIR + "nerolac-colours.json")),
            new Catalog("Nippon", "nippon", List.of(DIR + "nippon.json"))
    );

    @Override
    public void run(ApplicationArguments args) {
        int total = 0;
        for (Catalog c : CATALOGS) {
            try {
                total += importer.importBrand(c.name(), c.slug(), c.resources());
            } catch (Exception e) {
                log.error("[catalog-seed] failed to import {}: {}", c.slug(), e.getMessage(), e);
            }
        }
        if (total > 0) {
            log.info("[catalog-seed] imported {} new shades across {} brands", total, CATALOGS.size());
        }
    }
}
