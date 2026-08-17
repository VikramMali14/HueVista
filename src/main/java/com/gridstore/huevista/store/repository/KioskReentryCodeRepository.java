package com.gridstore.huevista.store.repository;

import com.gridstore.huevista.store.model.KioskReentryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KioskReentryCodeRepository extends JpaRepository<KioskReentryCode, String> {

    /**
     * The newest code sent to this address, consumed or not. Used for the resend
     * cooldown, which must count every code issued — reading only live ones would let a
     * caller reset the throttle by burning each code as it arrives.
     */
    Optional<KioskReentryCode> findTopByDestinationOrderByCreatedAtDesc(String destination);

    /** Live codes for an address, newest first — at most one should ever be usable. */
    List<KioskReentryCode> findByDestinationAndConsumedFalseOrderByCreatedAtDesc(String destination);
}
