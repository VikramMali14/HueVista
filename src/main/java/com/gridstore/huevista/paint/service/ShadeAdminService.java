package com.gridstore.huevista.paint.service;

import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.gridstore.huevista.project.repository.RegionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-only maintenance operations on the shade catalogue.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadeAdminService {

    private final ShadeRepository shadeRepository;
    private final RegionRepository regionRepository;

    /**
     * Deletes <em>every</em> shade across all brands and clears the applied-colour
     * reference (shade code + hex) each project region holds — regions point at a shade by
     * plain code, not a foreign key, so those strings would otherwise be left dangling.
     * The shade caches are evicted so the public list/detail endpoints stop serving the
     * now-deleted rows. Brands themselves are left intact.
     *
     * <p>Destructive and irreversible; re-populate via the admin seed/upload endpoints.
     *
     * @return how many shades were deleted and how many region references were cleared
     */
    @Transactional
    @CacheEvict(cacheNames = {"shades", "shade-families", "shade-detail"}, allEntries = true)
    public DeleteResult deleteAllShades() {
        long deletedShades = shadeRepository.count();
        int clearedRegions = regionRepository.clearAllAppliedColors();
        shadeRepository.deleteAllInBatch();
        log.warn("[admin] wiped shade catalogue — deleted {} shades, cleared applied colour on {} regions",
                deletedShades, clearedRegions);
        return new DeleteResult(deletedShades, clearedRegions);
    }

    /** Counts reported back to the admin caller. */
    public record DeleteResult(long deletedShades, int clearedRegions) {}
}
