package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * A LIVE account holding this address. Deleted rows keep a scrubbed placeholder
     * address, so they can never match here — but a merged-away guest account is
     * tombstoned the same way, and matching one would hand a returning customer a
     * session on the account they already emptied.
     */
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /** A user who has VERIFIED this mobile number — the only valid SMS-reset target. */
    Optional<User> findByPhoneNumberAndPhoneVerifiedTrue(String phoneNumber);

    /**
     * LIVE accounts that have VERIFIED this mobile number, oldest first.
     *
     * <p>A List rather than an Optional, and that is deliberate. Phone sign-in resolves
     * an account from a number and nothing else, so it is the one caller that cannot
     * afford to throw when the data says two rows hold the same verified number — a
     * 500 on the sign-in endpoint over historic data is worse than picking one. Oldest
     * first makes that pick deterministic and picks the account the customer has
     * actually been using; the caller logs the collision. Going forward
     * {@code VerificationService} refuses to create one.
     *
     * <p>Deleted rows are excluded: an account whose PII has been scrubbed keeps its
     * number until the row is purged, and matching one would sign somebody in to the
     * account they asked us to delete.
     */
    List<User> findByPhoneNumberAndPhoneVerifiedTrueAndDeletedAtIsNullOrderByCreatedAtAsc(String phoneNumber);

    List<User> findTop10ByOrderByCreatedAtDesc();

    long countByRole(com.gridstore.huevista.auth.model.UserRole role);

    /** The longest-standing account in a role — used to find the platform admin
     *  that owns the house distributor organization. */
    Optional<User> findFirstByRoleOrderByCreatedAtAsc(com.gridstore.huevista.auth.model.UserRole role);

    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :since")
    long countByCreatedAtAfter(@Param("since") LocalDateTime since);

    /** Admin console search — case-insensitive substring match on name or email.
     *  Ordering comes from the caller's Pageable (no ORDER BY here, so the two
     *  sorts can't conflict). */
    @Query("""
            SELECT u FROM User u
             WHERE LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    org.springframework.data.domain.Page<User> searchByNameOrEmail(
            @Param("q") String q, org.springframework.data.domain.Pageable pageable);
}
