package com.gridstore.huevista.painter.repository;

import com.gridstore.huevista.painter.model.PainterInvitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PainterInvitationRepository extends JpaRepository<PainterInvitation, String> {

    Optional<PainterInvitation> findByCode(String code);

    boolean existsByCode(String code);

    List<PainterInvitation> findByRetailerIdOrderByCreatedAtDesc(String retailerId);

    /**
     * Atomically claims an unused invitation (compare-and-set on usedAt IS NULL).
     * Two concurrent redeems can both pass an isUsed() pre-check; only one of
     * these UPDATEs matches the row, so exactly one redeem wins. Returns the
     * number of rows claimed (0 = already redeemed by someone else).
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "UPDATE PainterInvitation i SET i.usedAt = :now, i.usedByPainter = :painter " +
            "WHERE i.id = :id AND i.usedAt IS NULL")
    int claimIfUnused(String id, com.gridstore.huevista.auth.model.User painter,
                      java.time.LocalDateTime now);
}
