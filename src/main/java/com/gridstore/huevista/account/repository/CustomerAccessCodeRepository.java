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
     * Whether the end customer paid for this code themselves (a kiosk code), as a scalar
     * so the billing resolver can answer "does anyone's subscription pay for this run?"
     * without loading the row.
     */
    @Query("SELECT c.selfFunded FROM CustomerAccessCode c WHERE c.id = :id")
    Optional<Boolean> findSelfFundedById(@Param("id") String id);

    /**
     * Atomically take one colour-board PDF from a self-funded code's own allowance —
     * one board per project it paid for. Returns 1 when charged, 0 when spent. Kiosk
     * boards must not come out of the shop's monthly PDF limit: the walk-in already
     * paid for the board along with the project.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CustomerAccessCode c SET c.pdfDownloadsUsed = c.pdfDownloadsUsed + 1
             WHERE c.id = :id AND c.pdfDownloadsUsed < c.projectQuota
            """)
    int consumeSelfFundedPdf(@Param("id") String id);

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

    /**
     * Cancel a code nobody has redeemed. The {@code usedByUser IS NULL} guard makes this a
     * compare-and-set, so a revoke racing a redemption loses cleanly rather than pulling a
     * code out from under the customer who has just claimed it.
     */
    @Modifying
    @Query("""
            UPDATE CustomerAccessCode c SET c.revokedAt = :now
             WHERE c.id = :id AND c.usedByUser IS NULL AND c.revokedAt IS NULL
            """)
    int revokeIfUnused(@Param("id") String id, @Param("now") LocalDateTime now);
}
