package com.gridstore.huevista.maskreport.repository;

import com.gridstore.huevista.maskreport.model.MaskReport;
import com.gridstore.huevista.maskreport.model.MaskReportStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MaskReportRepository extends JpaRepository<MaskReport, String> {

    /**
     * The admin queue. Everything the list row renders is fetch-joined, because the
     * alternative is four lazy SELECTs per report and this page exists to be
     * skimmed. The access code drags its organization along for the shop name.
     */
    @Query("""
            SELECT r FROM MaskReport r
              LEFT JOIN FETCH r.project p
              LEFT JOIN FETCH r.reporter
              LEFT JOIN FETCH r.accessCode c
              LEFT JOIN FETCH c.organization
              LEFT JOIN FETCH r.resolvedBy
             WHERE r.status IN :statuses
             ORDER BY r.createdAt DESC
            """)
    List<MaskReport> findForQueue(@Param("statuses") Collection<MaskReportStatus> statuses,
                                  Pageable pageable);

    /**
     * The reporter's still-open report on this project, if they have one.
     *
     * Someone who is unhappy with a mask presses the button again after the re-run
     * is also wrong — that is the same complaint, not a second one, so the service
     * folds it into this row instead of stacking near-identical tickets in the queue.
     */
    Optional<MaskReport> findFirstByProjectIdAndReporterIdAndStatusNotOrderByCreatedAtDesc(
            String projectId, String reporterId, MaskReportStatus excluded);

    /** Same, for a guest working under an access code (no user account). */
    Optional<MaskReport> findFirstByProjectIdAndAccessCodeIdAndReporterIsNullAndStatusNotOrderByCreatedAtDesc(
            String projectId, String accessCodeId, MaskReportStatus excluded);

    long countByStatus(MaskReportStatus status);
}
