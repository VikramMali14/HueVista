package com.gridstore.huevista.common.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records sensitive actions to an immutable audit table. Best-effort: persisted in
 * its OWN transaction (REQUIRES_NEW) so an audit failure never rolls back — nor is
 * rolled back by — the action it describes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actorUserId, String action, String targetType, String targetId, String detail) {
        try {
            repository.save(AuditLog.builder()
                    .actorUserId(actorUserId)
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(detail)
                    .build());
        } catch (Exception e) {
            log.warn("Audit write failed for action={} target={}:{}: {}", action, targetType, targetId, e.getMessage());
        }
    }
}
