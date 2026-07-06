package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.OAuthExchangeCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface OAuthExchangeCodeRepository extends JpaRepository<OAuthExchangeCode, Long> {

    Optional<OAuthExchangeCode> findByCodeHash(String codeHash);

    /**
     * Atomically consumes a live code — compare-and-set on {@code consumed}, so a
     * replayed (or raced) exchange matches 0 rows and must be rejected. The expiry
     * check lives in the same UPDATE for the same reason.
     */
    @Modifying
    @Query("""
            UPDATE OAuthExchangeCode c
               SET c.consumed = true
             WHERE c.codeHash = :hash AND c.consumed = false AND c.expiresAt > :now
            """)
    int consume(@Param("hash") String hash, @Param("now") LocalDateTime now);

    /** Housekeeping: a user's stale codes are dropped whenever a new one is minted. */
    @Modifying
    void deleteByUserId(String userId);
}
