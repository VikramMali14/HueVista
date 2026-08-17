package com.gridstore.huevista.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Writes off AI image credits whose year has run out.
 *
 * <p>Deliberately thinner than {@link RewardPointsExpiryJob}, which warns twice before it
 * sweeps. Points are EARNED — a shop can be surprised to have them and surprised to lose
 * them, so the notices are the product. A dated AI credit is BOUGHT off a cart that says
 * "valid for a year" on the line, and the wallet panel carries the date from the moment it
 * lands, so the buyer has been told twice already by the time this runs.
 *
 * <p>Runs at 02:30, after the points sweep at 02:00 rather than alongside it: they touch
 * different ledgers, and staggering them keeps one slow night from being two.
 *
 * <p>Idempotent. A batch is closed by a compare-and-set on what it still holds, so a job
 * that runs twice — or a deploy that restarts it mid-pass — writes nothing off twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCreditExpiryJob {

    private final AiCreditService aiCreditService;

    @Scheduled(cron = "0 30 2 * * *")
    public void run() {
        int expired = aiCreditService.expireDueLots();
        if (expired > 0) {
            log.info("AI credit sweep: {} credit(s) expired", expired);
        }
    }
}
