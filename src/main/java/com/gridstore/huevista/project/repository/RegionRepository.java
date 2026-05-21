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
}
