package com.gridstore.huevista.painter.repository;

import com.gridstore.huevista.painter.model.PaintJob;
import com.gridstore.huevista.painter.model.PaintJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaintJobRepository extends JpaRepository<PaintJob, String> {

    List<PaintJob> findByPainterIdOrderByCreatedAtDesc(String painterId);

    List<PaintJob> findByRetailerIdOrderByCreatedAtDesc(String retailerId);

    List<PaintJob> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<PaintJob> findByPainterIdAndStatusInOrderByCreatedAtDesc(String painterId, List<PaintJobStatus> statuses);

    Optional<PaintJob> findByProjectId(String projectId);
}
