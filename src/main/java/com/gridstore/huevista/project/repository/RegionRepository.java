package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.project.model.Region;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RegionRepository extends JpaRepository<Region, Long> {

    List<Region> findByProjectIdOrderByDisplayOrderAsc(String projectId);

    Optional<Region> findByIdAndProjectId(Long id, String projectId);

    void deleteByProjectId(String projectId);
}
