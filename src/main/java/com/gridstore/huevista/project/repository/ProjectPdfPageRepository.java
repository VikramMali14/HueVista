package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.project.model.ProjectPdfPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectPdfPageRepository extends JpaRepository<ProjectPdfPage, String> {

    /**
     * Every combo this project handed over, in the order the customer saw them: board
     * first, then position inside it.
     *
     * The shades are fetch-joined because the caller always wants them — the selection
     * page renders each combo from its hexes, so a page without its shades is not a
     * usable answer, and lazy-loading them is one extra query per combo.
     */
    @Query("""
           SELECT DISTINCT p FROM ProjectPdfPage p
           LEFT JOIN FETCH p.shades
           WHERE p.project.id = :projectId
           ORDER BY p.boardIndex ASC, p.pageIndex ASC
           """)
    List<ProjectPdfPage> findByProjectIdWithShades(@Param("projectId") String projectId);

    /**
     * How many combinations each of these projects handed over, in one query.
     *
     * <p>For the render picker, which shows a count per room. Asking per project would be
     * an N+1 across a list that is as long as the account's finished work, and the count is
     * the only thing it needs — not the pages, and certainly not their shades.
     *
     * <p>Projects with no pages simply do not come back; the caller reads a missing key as
     * zero. Callers must guard against an empty collection — JPQL {@code IN ()} is invalid.
     */
    @Query("""
           SELECT p.project.id, COUNT(p)
           FROM ProjectPdfPage p
           WHERE p.project.id IN :projectIds
           GROUP BY p.project.id
           """)
    List<Object[]> countByProjectIds(@Param("projectIds") java.util.Collection<String> projectIds);

    /**
     * One page, scoped to its project. The project predicate IS the ownership guard: the
     * caller has already been checked against the project, so a page that does not belong
     * to it simply is not found.
     */
    @Query("""
           SELECT p FROM ProjectPdfPage p
           LEFT JOIN FETCH p.shades
           WHERE p.id = :pageId AND p.project.id = :projectId
           """)
    Optional<ProjectPdfPage> findByIdAndProjectId(@Param("pageId") String pageId,
                                                  @Param("projectId") String projectId);
}
