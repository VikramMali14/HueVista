package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.model.ProjectCredit;
import com.gridstore.huevista.billing.repository.ProjectCreditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Issues and spends the paid-for-but-not-yet-created projects in
 * {@link ProjectCredit}.
 *
 * The claim is deliberately two-phase — {@link #claim} before the project row exists,
 * {@link #attach} once it does. A project id can only be handed out by the insert, and
 * doing the claim afterwards would leave a window where two parallel creations both saw
 * the same unspent credit. Claiming first means the compare-and-set decides the winner
 * before any project exists; if the creation then fails, {@link #release} puts the
 * credit back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectCreditLedger {

    private final ProjectCreditRepository creditRepository;

    /** Credit a verified purchase. Returns the credit so the caller can report its terms. */
    @Transactional
    public ProjectCredit issue(String userId, int pointsSpent, int validDays, ProjectCredit.Source source) {
        ProjectCredit credit = creditRepository.save(ProjectCredit.builder()
                .userId(userId)
                .pointsSpent(pointsSpent)
                .validDays(validDays)
                .source(source)
                .build());
        log.info("Project credit issued: user={} price={} validDays={} source={}",
                userId, pointsSpent, validDays, source);
        return credit;
    }

    @Transactional(readOnly = true)
    public int available(String userId) {
        return creditRepository.countAvailable(userId);
    }

    /**
     * Take one unspent credit for {@code userId}, oldest first, or empty when they hold
     * none. Losing the compare-and-set means a parallel request took that credit, so the
     * next one is tried rather than failing outright.
     */
    @Transactional
    public Optional<ProjectCredit> claim(String userId) {
        LocalDateTime now = LocalDateTime.now();
        for (ProjectCredit credit : creditRepository.findAvailable(userId)) {
            if (creditRepository.consume(credit.getId(), null, now) == 1) {
                credit.setConsumedAt(now);
                return Optional.of(credit);
            }
        }
        return Optional.empty();
    }

    /** Point a claimed credit at the project it became. */
    @Transactional
    public void attach(String creditId, String projectId) {
        creditRepository.findById(creditId).ifPresent(credit -> {
            credit.setProjectId(projectId);
            creditRepository.save(credit);
        });
    }

    /** Return a claimed credit that never became a project. */
    @Transactional
    public void release(String creditId) {
        creditRepository.release(creditId);
        log.info("Project credit {} released back — the project it was claimed for was not created",
                creditId);
    }
}
