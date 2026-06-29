package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.RefreshToken;
import com.gridstore.huevista.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {
    Optional<RefreshToken> findByToken(String token);

    /**
     * Atomically delete a single token by id, returning how many rows were
     * actually removed (0 or 1). Used by token rotation to settle concurrent
     * refresh races: only the caller that gets a count of 1 "won" and may issue
     * a new pair. Unlike the entity delete ({@link #delete}), this bulk delete
     * does not trip Hibernate's optimistic "expected 1 row, got 0" check when a
     * racing request already removed the row.
     */
    @Modifying
    @Query("delete from RefreshToken rt where rt.id = :id")
    int deleteByIdReturningCount(@Param("id") String id);

    @Modifying
    void deleteByUser(User user);

    /** Bulk-delete expired tokens (used by the nightly cleanup job). */
    @Modifying
    long deleteByExpiryDateBefore(Instant cutoff);
}
