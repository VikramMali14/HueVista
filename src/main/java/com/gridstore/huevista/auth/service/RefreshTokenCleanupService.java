package com.gridstore.huevista.auth.service;

import com.gridstore.huevista.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Nightly purge of expired refresh tokens so the table doesn't accumulate dead
 * rows. (Tokens are also deleted on use/rotation, but abandoned sessions never
 * come back to be cleaned otherwise.) Scheduling is enabled on the application.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RefreshTokenCleanupService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "0 0 3 * * *") // daily at 03:00 server time
    @Transactional
    public void purgeExpired() {
        long removed = refreshTokenRepository.deleteByExpiryDateBefore(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired refresh tokens", removed);
        }
    }
}
