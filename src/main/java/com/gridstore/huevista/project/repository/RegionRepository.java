package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.project.model.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByProjectIdOrderByDisplayOrderAsc(String projectId);

    Optional<Region> findByIdAndProjectId(Long id, String projectId);

    void deleteByProjectId(String projectId);

    int countByProjectId(String projectId);

    /**
     * Wipes everything the auto-pipeline produced for a project — anything that
     * isn't MANUAL (user-clicked) — including legacy rows with category=null
     * from before the category enum existed. Called at the start of each
     * auto-segment retry so we don't accumulate stale MAIN_WALL/ACCENT_WALL/
     * TRIM regions across runs.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Region r WHERE r.project.id = :projectId " +
            "AND (r.category IS NULL OR r.category <> com.gridstore.huevista.project.model.RegionCategory.MANUAL)")
    void deleteAutoRegionsByProjectId(String projectId);

    @Query("SELECT r FROM Region r WHERE r.project.id = :projectId " +
            "AND (r.category IS NULL OR r.category <> com.gridstore.huevista.project.model.RegionCategory.MANUAL)")
    List<Region> findAutoRegionsByProjectId(String projectId);

    /**
     * Bulk color update — one UPDATE per region instead of a SELECT + dirty-check
     * save round-trip each. The project-id predicate doubles as the ownership
     * guard (a region id from another project simply matches zero rows).
     * clearAutomatically so the response re-read below sees the new values.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Region r SET r.appliedShadeCode = :shadeCode, r.appliedHexCode = :hexCode " +
            "WHERE r.id = :regionId AND r.project.id = :projectId")
    int updateAppliedColor(Long regionId, String projectId, String shadeCode, String hexCode);
}
