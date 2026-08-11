package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.project.model.ProjectRender;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRenderRepository extends JpaRepository<ProjectRender, String> {

    /** Every render of this project, newest first. */
    List<ProjectRender> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** One render, scoped to its project — the project predicate is the ownership guard. */
    Optional<ProjectRender> findByIdAndProjectId(String id, String projectId);
}
