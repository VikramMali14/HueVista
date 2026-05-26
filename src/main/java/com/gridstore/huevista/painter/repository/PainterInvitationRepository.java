package com.gridstore.huevista.painter.repository;

import com.gridstore.huevista.painter.model.PainterInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PainterInvitationRepository extends JpaRepository<PainterInvitation, String> {

    Optional<PainterInvitation> findByCode(String code);

    boolean existsByCode(String code);

    List<PainterInvitation> findByRetailerIdOrderByCreatedAtDesc(String retailerId);
}
