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

    /** Lookup by the SHA-256 hex of the raw token (tokens are stored hashed). */
    Optional<RefreshToken> findByToken(String tokenHash);

    /**
     * Atomically consume a live token for rotation, returning how many rows were
     * updated (0 or 1). Exactly one of several racing refresh requests "wins"
     * (count 1); the losers get 0 because the {@code usedAt is null} guard no
     * longer matches. A bulk update (not an entity save) so a lost race can never
     * trip Hibernate's optimistic "expected 1 row" check.
     */
    @Modifying
    @Query("update RefreshToken rt set rt.usedAt = :now where rt.id = :id and rt.usedAt is null")
    int markUsedReturningCount(@Param("id") String id, @Param("now") Instant now);

    /**
     * Atomically delete a single token by id, returning how many rows were
     * actually removed (0 or 1). Used to drop an expired token without racing
     * a concurrent delete.
     */
    @Modifying
    @Query("delete from RefreshToken rt where rt.id = :id")
    int deleteByIdReturningCount(@Param("id") String id);

    @Modifying
    void deleteByUser(User user);

    /** Bulk-delete expired tokens (used by the nightly cleanup job). */
    @Modifying
    long deleteByExpiryDateBefore(Instant cutoff);

    /**
     * Bulk-delete tokens consumed by rotation before the cutoff (i.e. well past
     * the reuse grace window) so the table doesn't accumulate dead rows between
     * nightly runs of the expiry purge.
     */
    @Modifying
    long deleteByUsedAtBefore(Instant cutoff);
}
