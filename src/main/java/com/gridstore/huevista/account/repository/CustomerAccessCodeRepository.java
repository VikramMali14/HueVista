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
}
