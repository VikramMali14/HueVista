package com.gridstore.huevista.common.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();

    // Admin viewer — newest first, optionally narrowed to one action type.
    org.springframework.data.domain.Page<AuditLog> findAllByOrderByCreatedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<AuditLog> findByActionOrderByCreatedAtDesc(
            String action, org.springframework.data.domain.Pageable pageable);
}
