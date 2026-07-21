package com.gridstore.huevista.painter.repository;

import com.gridstore.huevista.painter.model.PainterLinkStatus;
import com.gridstore.huevista.painter.model.PainterRetailerLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PainterRetailerLinkRepository extends JpaRepository<PainterRetailerLink, String> {

    List<PainterRetailerLink> findByRetailerIdAndStatus(String retailerId, PainterLinkStatus status);

    List<PainterRetailerLink> findByRetailerIdInAndStatus(java.util.Collection<String> retailerIds, PainterLinkStatus status);

    List<PainterRetailerLink> findByStatus(PainterLinkStatus status);

    List<PainterRetailerLink> findByPainterIdAndStatus(String painterId, PainterLinkStatus status);

    Optional<PainterRetailerLink> findByPainterIdAndRetailerId(String painterId, String retailerId);

    boolean existsByPainterIdAndRetailerIdAndStatus(String painterId, String retailerId, PainterLinkStatus status);
}
