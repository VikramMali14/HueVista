package com.gridstore.huevista.account.repository;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CustomerAccessCodeRepository extends JpaRepository<CustomerAccessCode, String> {

    Optional<CustomerAccessCode> findByCode(String code);

    /** The access code a redeemed customer account was created from (one per account). */
    Optional<CustomerAccessCode> findFirstByUsedByUserIdOrderByCreatedAtDesc(String usedByUserId);

    List<CustomerAccessCode> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);

    boolean existsByCode(String code);

    /** Owning organization id without initializing the lazy association — for the async worker. */
    @Query("SELECT c.organization.id FROM CustomerAccessCode c WHERE c.id = :id")
    Optional<String> findOrganizationIdById(@Param("id") String id);

    /**
     * Per-org access-code totals for the network report: [orgId, issued, redeemed].
     * COUNT(c.usedAt) only counts non-null values, i.e. consumed codes. Callers must
     * guard against an empty collection (JPQL IN () is invalid).
     */
    @Query("""
            SELECT c.organization.id, COUNT(c), COUNT(c.usedAt)
              FROM CustomerAccessCode c
             WHERE c.organization.id IN :orgIds
             GROUP BY c.organization.id
            """)
    List<Object[]> issuedAndRedeemedByOrgIds(@Param("orgIds") java.util.Collection<String> orgIds);

    /**
     * Atomically consumes a code for a signed-in user. The {@code usedByUser IS NULL
     * AND usedAt IS NULL} guard makes this a compare-and-set: when two requests race
     * on the same code, exactly one UPDATE matches and returns 1 — the loser gets 0
     * and must treat the code as already used.
     */
    @Modifying
    @Query("""
            UPDATE CustomerAccessCode c
               SET c.usedByUser = :user, c.usedAt = :now
             WHERE c.id = :id AND c.usedByUser IS NULL AND c.usedAt IS NULL
            """)
    int consumeForUser(@Param("id") String id, @Param("user") User user, @Param("now") LocalDateTime now);

    /** Atomic guest-redemption variant of {@link #consumeForUser}. */
    @Modifying
    @Query("""
            UPDATE CustomerAccessCode c
               SET c.usedAt = :now, c.guestRedeemed = true
             WHERE c.id = :id AND c.usedByUser IS NULL AND c.usedAt IS NULL
            """)
    int consumeForGuest(@Param("id") String id, @Param("now") LocalDateTime now);

    /**
     * Who (if anyone) consumed the code for an ACCOUNT — as a scalar projection so
     * the answer comes from the database, not the persistence context's possibly
     * stale managed entity. Used after a lost guest-redeem CAS to tell "lost to
     * another guest re-entry" (fine) apart from "lost to an account redeem" (reject).
     * The inner join means an empty result = no account consumed it.
     */
    @Query("SELECT u.id FROM CustomerAccessCode c JOIN c.usedByUser u WHERE c.id = :id")
    java.util.List<String> usedByAccountUserIds(@Param("id") String id);

    /**
     * Atomically spend one of this code's held image credits. The {@code reservedImages
     * > 0} guard makes it a compare-and-set: concurrent segmentations of two projects
     * under the same code can never both claim the last hold. Returns 1 when a hold was
     * taken (the caller then moves it on the subscription too), 0 when the code has none
     * left — e.g. a legacy code issued before holds existed, or a shop that granted more
     * projects than it reserved.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerAccessCode c SET c.reservedImages = c.reservedImages - 1
             WHERE c.id = :id AND c.reservedImages > 0
            """)
    int consumeReservedImage(@Param("id") String id);

    /**
     * Atomically zero a code's remaining holds, stamping when they were returned.
     * The {@code quotaReleasedAt IS NULL} guard makes the release idempotent, so a
     * revoke racing the expiry sweep can never refund the same code twice. Returns
     * the number of rows updated (1 = this caller won and must credit the shop back).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerAccessCode c SET c.reservedImages = 0, c.quotaReleasedAt = :now
             WHERE c.id = :id AND c.quotaReleasedAt IS NULL
            """)
    int markQuotaReleased(@Param("id") String id, @Param("now") LocalDateTime now);

    /** Atomically stamp a code revoked; 0 when it was already revoked or already used. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerAccessCode c SET c.revokedAt = :now
             WHERE c.id = :id AND c.revokedAt IS NULL AND c.usedAt IS NULL AND c.usedByUser IS NULL
            """)
    int revokeIfUnused(@Param("id") String id, @Param("now") LocalDateTime now);

    /**
     * Codes whose holds are dead money: expired, never redeemed, never revoked, and not
     * yet refunded. The daily sweep hands these holds back to the issuing shop — without
     * it, quota reserved for a customer who never walked in is lost forever.
     */
    @Query("""
            SELECT c FROM CustomerAccessCode c
             WHERE c.expiresAt < :cutoff AND c.usedAt IS NULL AND c.usedByUser IS NULL
               AND c.revokedAt IS NULL AND c.quotaReleasedAt IS NULL AND c.reservedImages > 0
            """)
    List<CustomerAccessCode> findExpiredUnredeemedWithHolds(@Param("cutoff") LocalDateTime cutoff);
}
