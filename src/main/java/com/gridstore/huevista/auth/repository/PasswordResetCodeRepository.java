package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.PasswordResetCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {

    /** Most recent code (consumed or not) — for the resend cooldown. */
    Optional<PasswordResetCode> findTopByUserIdOrderByCreatedAtDesc(String userId);

    /** Active codes, row-locked, so confirm is atomic under concurrency. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PasswordResetCode c where c.userId = ?1 and c.consumed = false order by c.createdAt desc")
    List<PasswordResetCode> findActiveForUpdate(String userId);

    List<PasswordResetCode> findByUserIdAndConsumedFalse(String userId);
}
