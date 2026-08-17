package com.gridstore.huevista.project.repository;

import com.gridstore.huevista.project.model.ProjectRender;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRenderRepository extends JpaRepository<ProjectRender, String> {

    /** Every render of this project, newest first. */
    List<ProjectRender> findByProjectIdOrderByCreatedAtDesc(String projectId);

    /**
     * Every FINISHED image this account owns, newest first — the "my AI images" shelf.
     *
     * Scoped by the project's owner, which is also the ownership guard: there is no id in
     * the query for a caller to substitute, so it can only ever return their own rooms.
     * Rooms belonging to a walk-in access code (user null) are excluded by the join, which
     * is right — a guest has no account for this page to belong to.
     *
     * <p>READY only. A queued or running render is a thing the studio is polling, and a
     * failed one has already handed its credit back; neither is a picture, and a gallery
     * of spinners and apologies is not what somebody comes here for.
     *
     * <p>The project, page and shades are all fetch-joined because the response needs
     * every one of them: the room's name, the combination, and the colours to print. Left
     * lazy this is the textbook N+1 — an account with thirty images would issue ninety
     * further queries building one page.
     *
     * <p>The status is bound rather than written as a literal. A JPQL enum literal has to
     * name the nested {@code Status} type, which is exactly the sort of expression that
     * parses on one Hibernate version and fails to start the application on the next —
     * and a repository query is only validated when the context boots, so the cost of
     * being clever here is a deployment that does not come up.
     */
    @Query("""
           SELECT DISTINCT r FROM ProjectRender r
           JOIN FETCH r.project p
           LEFT JOIN FETCH r.page pg
           LEFT JOIN FETCH pg.shades
           WHERE p.user.id = :userId AND r.status = :status
           ORDER BY r.createdAt DESC
           """)
    List<ProjectRender> findByOwnerAndStatus(@Param("userId") String userId,
                                             @Param("status") ProjectRender.Status status);

    /**
     * Every FINISHED image made in rooms created against one access code, newest first —
     * the shop's side of the same shelf.
     *
     * <p>Keyed by the CODE rather than by the customer, which is the only key a shop
     * actually holds: the counter knows which code it issued and to whom, and the
     * customer's account id never appears in the portal. It is also the narrower claim —
     * a code covers the rooms this shop paid for, not everything the account has ever
     * made with somebody else's code or its own money. A shop seeing a picture from a
     * room it had no part in would be a leak dressed up as a feature.
     *
     * <p>Same READY-only rule and the same fetch joins as the owner query above, for the
     * same reasons; the ownership check is the caller's ({@code requireManagedCode}),
     * because unlike that query this one takes an id somebody could substitute.
     */
    @Query("""
           SELECT DISTINCT r FROM ProjectRender r
           JOIN FETCH r.project p
           LEFT JOIN FETCH r.page pg
           LEFT JOIN FETCH pg.shades
           WHERE p.accessCode.id = :accessCodeId AND r.status = :status
           ORDER BY r.createdAt DESC
           """)
    List<ProjectRender> findByAccessCodeAndStatus(@Param("accessCodeId") String accessCodeId,
                                                  @Param("status") ProjectRender.Status status);

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
