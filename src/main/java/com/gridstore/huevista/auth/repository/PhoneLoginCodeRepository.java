package com.gridstore.huevista.auth.repository;

import com.gridstore.huevista.auth.model.PhoneLoginCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PhoneLoginCodeRepository extends JpaRepository<PhoneLoginCode, Long> {

    /**
     * The most recent code for this number, consumed or not.
     *
     * <p>Consumed status is deliberately ignored: this drives the resend cooldown, and a
     * cooldown that a successful sign-in resets is one an attacker can clear at will.
     */
    Optional<PhoneLoginCode> findTopByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    /**
     * Active codes for this number, row-locked FOR UPDATE so that the
     * check-increment-consume in the verify step is atomic against concurrent guesses.
     * Without the lock, parallel requests each read {@code attempts} before any of them
     * writes it, and the attempt limit counts one try instead of ten.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PhoneLoginCode c where c.phoneNumber = ?1 and c.consumed = false order by c.createdAt desc")
    List<PhoneLoginCode> findActiveForUpdate(String phoneNumber);

    /** Un-consumed codes for a number, to invalidate when a newer one is issued. */
    List<PhoneLoginCode> findByPhoneNumberAndConsumedFalse(String phoneNumber);

    /** How many codes this number has been sent since {@code since} — the daily cap. */
    long countByPhoneNumberAndCreatedAtAfter(String phoneNumber, LocalDateTime since);

    /** Housekeeping: codes that can no longer be used by anybody. */
    long deleteByExpiresAtBefore(LocalDateTime cutoff);
}
