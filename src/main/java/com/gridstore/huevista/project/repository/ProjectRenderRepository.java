package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.project.model.ProjectRender;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRenderRepository extends JpaRepository<ProjectRender, String> {

    /** Every render of this project, newest first. */
    List<ProjectRender> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /** One render, scoped to its project — the project predicate is the ownership guard. */
    Optional<ProjectRender> findByIdAndProjectId(String id, String projectId);

    /**
     * Renders left in a non-terminal state since before a cutoff — the sweeper's input.
     *
     * Unbounded deliberately, unlike the payment sweeper's batch: there is at most one
     * render per project and the terminal ones are excluded, so the rows this can return
     * are the ones currently in flight plus whatever a restart stranded.
     */
    List<ProjectRender> findByStatusInAndCreatedAtBefore(
            Collection<ProjectRender.Status> statuses, LocalDateTime cutoff);
}
