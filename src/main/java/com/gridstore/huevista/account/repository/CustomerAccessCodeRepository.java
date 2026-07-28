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
     * Codes whose holds are dead money: expired, never revoked, not yet refunded, and
     * still holding image credits. The daily sweep hands these holds back to the issuing
     * shop — without it, quota reserved for projects nobody created is lost forever.
     *
     * <p>Deliberately NOT limited to unredeemed codes. It once was, and that was the
     * larger half of the leak: a code redeemed for five projects whose customer created
     * two left THREE credits held on the shop's subscription with no path back. Revoking
     * refuses on a redeemed code (the customer may have work under it), the old sweep
     * skipped it, and {@code reservedImages} deliberately survives a renewal — so those
     * credits were subtracted from the shop's effective quota in every future billing
     * period, forever. A shop issuing codes at a steady rate simply ran out.
     *
     * <p>Expiry is the safe moment to reclaim either way: past it no project can be
     * created against the code and no run can be billed to it, so a remaining hold can
     * only ever be a project that will never exist.
     */
    @Query("""
            SELECT c FROM CustomerAccessCode c
             WHERE c.expiresAt < :cutoff
               AND c.revokedAt IS NULL AND c.quotaReleasedAt IS NULL AND c.reservedImages > 0
            """)
    List<CustomerAccessCode> findExpiredWithHolds(@Param("cutoff") LocalDateTime cutoff);
}
