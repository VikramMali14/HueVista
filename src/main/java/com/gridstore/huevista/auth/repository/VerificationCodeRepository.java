package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.VerificationChannel;
import com.gridstore.huevista.auth.model.VerificationCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    /** Most recent code (consumed or not) — used for the resend cooldown so that
     *  consuming/expiring a code can't reset the throttle. */
    Optional<VerificationCode> findTopByUserIdAndChannelOrderByCreatedAtDesc(
            String userId, VerificationChannel channel);

    /** Active (un-consumed) codes, row-locked FOR UPDATE so confirm()'s
     *  check-increment-consume is atomic under concurrent requests. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VerificationCode v where v.userId = ?1 and v.channel = ?2 and v.consumed = false order by v.createdAt desc")
    List<VerificationCode> findActiveForUpdate(String userId, VerificationChannel channel);

    /** All un-consumed codes for a user on a channel (to invalidate on re-issue). */
    List<VerificationCode> findByUserIdAndChannelAndConsumedFalse(
            String userId, VerificationChannel channel);
}
