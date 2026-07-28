package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.billing.model.RewardPointsLot;
import com.gridstore.huevista.billing.repository.RewardPointsLotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warns shops about points that are about to expire, then writes off the ones that have.
 *
 * Two notices per batch of points: one {@code app.points.expiry-warning-days} ahead (10
 * days by default) and one on the expiry day itself. Both are addressed from the lot's
 * own date, so a shop earning steadily gets a notice per batch rather than one vague
 * warning about "some points".
 *
 * <p>Order matters here. Notices go out BEFORE the sweep, so the last-day mail never
 * lands after the points it is about have already gone. And notices are aggregated per
 * shop per run: a busy kiosk can have several lots falling due the same day, and that is
 * one e-mail about a total, not five about fragments.
 *
 * <p>Sent flags on the lot make the daily run idempotent — a job that runs twice, or a
 * deploy that restarts it mid-pass, does not re-mail anybody.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardPointsExpiryJob {

    private final RewardPointsLotRepository lotRepository;
    private final RewardPointsService pointsService;
    private final BillingEmailService billingEmailService;
    private final PricingService pricingService;

    /** Daily at 02:00, before the subscription sweep at 01:00 has any bearing on points. */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void run() {
        sendWarningNotices();
        sendLastDayNotices();
        int expired = pointsService.expireDueLots();
        if (expired > 0) {
            log.info("Reward points sweep: {} lot(s) expired", expired);
        }
    }

    /** "Your points expire in 10 days" — one mail per shop, covering that day's batches. */
    void sendWarningNotices() {
        LocalDateTime now = LocalDateTime.now();
        // The day that falls `warning-days` out, taken as a whole day so the job catches a
        // lot whatever time of day it was originally earned.
        LocalDate target = now.toLocalDate().plusDays(pricingService.pointsExpiryWarningDays());
        List<RewardPointsLot> lots = lotRepository.expiringBetween(
                target.atStartOfDay(), target.plusDays(1).atStartOfDay());

        forEachShop(lots, RewardPointsLot::getExpiryWarningSentAt, (userId, points, expiresAt) -> {
            billingEmailService.sendPointsExpiringSoon(
                    userId, points, expiresAt, pricingService.pointsExpiryWarningDays());
            return null;
        }, lot -> lot.setExpiryWarningSentAt(now));
    }

    /** "Your points expire today" — the last chance to spend them. */
    void sendLastDayNotices() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<RewardPointsLot> lots = lotRepository.expiringBetween(
                today.atStartOfDay(), today.plusDays(1).atStartOfDay());

        forEachShop(lots, RewardPointsLot::getExpiryNoticeSentAt, (userId, points, expiresAt) -> {
            billingEmailService.sendPointsExpiringToday(userId, points, expiresAt);
            return null;
        }, lot -> lot.setExpiryNoticeSentAt(now));
    }

    /**
     * Group the day's lots by shop, skip any already notified, mail the total once, then
     * stamp every lot the mail covered.
     */
    private void forEachShop(List<RewardPointsLot> lots,
                             java.util.function.Function<RewardPointsLot, LocalDateTime> sentAt,
                             Notifier notify,
                             java.util.function.Consumer<RewardPointsLot> stamp) {
        Map<String, List<RewardPointsLot>> byShop = new LinkedHashMap<>();
        for (RewardPointsLot lot : lots) {
            if (sentAt.apply(lot) != null) continue;
            byShop.computeIfAbsent(lot.getUserId(), k -> new java.util.ArrayList<>()).add(lot);
        }
        byShop.forEach((userId, shopLots) -> {
            int points = shopLots.stream().mapToInt(RewardPointsLot::getPointsRemaining).sum();
            if (points <= 0) return;
            LocalDateTime expiresAt = shopLots.get(0).getExpiresAt();
            try {
                notify.send(userId, points, expiresAt);
            } catch (Exception e) {
                // Best-effort, like every other billing mail: a mail outage must not stop
                // the sweep or leave points alive past their date. Unstamped lots simply
                // get the notice on tomorrow's run if there is still time.
                log.warn("Points expiry notice failed for {}: {}", userId, e.getMessage());
                return;
            }
            shopLots.forEach(lot -> {
                stamp.accept(lot);
                lotRepository.save(lot);
            });
        });
    }

    @FunctionalInterface
    private interface Notifier {
        Void send(String userId, int points, LocalDateTime expiresAt);
    }
}
