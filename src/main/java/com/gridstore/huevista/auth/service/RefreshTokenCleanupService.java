package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nightly purge of dead refresh-token rows: expired tokens (abandoned sessions)
 * and tokens consumed by rotation. Consumed rows are kept briefly after use so
 * parallel refreshes within the grace window can be honoured and later reuse can
 * be flagged as theft — but past an hour they are pure dead weight.
 * Scheduling is enabled on the application.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    /** Comfortably longer than any sane app.refresh-token.reuse-grace-ms value. */
    private static final java.time.Duration USED_RETENTION = java.time.Duration.ofHours(1);

    @Scheduled(cron = "0 0 3 * * *") // daily at 03:00 server time
    @Transactional
    public void purgeExpired() {
        Instant now = Instant.now();
        long removed = refreshTokenRepository.deleteByExpiryDateBefore(now);
        removed += refreshTokenRepository.deleteByUsedAtBefore(now.minus(USED_RETENTION));
        if (removed > 0) {
            log.info("Purged {} expired/consumed refresh tokens", removed);
        }
    }
}
