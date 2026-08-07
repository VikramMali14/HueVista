package com.gridstore.huevista.billing.service;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.RewardPointsLot;
import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import com.gridstore.huevista.billing.repository.RewardPointsLotRepository;
import com.gridstore.huevista.billing.repository.RewardPointsTransactionRepository;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The point ledger: the only balance in the product.
 *
 * Points arrive two ways — earned when a walk-in buys through the shop's kiosk link, or
 * bought outright at {@link PricingService#rupeesPerPoint()} rupees each — and are spent
 * on everything chargeable besides the monthly plan: extra images, auto-masks, projects,
 * reopens. There is no prepaid rupee wallet beside this and no per-item cash checkout;
 * both existed once and were removed, so a purchase has exactly one price in exactly one
 * unit.
 *
 * <p>Bought and earned points are deliberately indistinguishable once credited. They
 * expire on the same one-year clock and buy the same things at the same prices, so the
 * ledger never has to answer "which kind of point was that" — which is what would make a
 * refund, an expiry or a spend ambiguous.
 *
 * <p><b>Retailers only.</b> Everything points buy is shop-side (plan overage, projects),
 * so a customer or painter account can neither earn nor spend them. The check is here
 * rather than in a controller so no future caller can route around it.
 *
 * <p>Points never leave as cash. There is no payout method on this class and there must
 * not be one — converting points to a bank transfer would make every kiosk sale a payment
 * collected on the shop's behalf, which is the pattern the flat-price kiosk removed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardPointsService {

    private final RewardPointsLotRepository lotRepository;
    private final RewardPointsTransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final PricingService pricingService;

    /** Spendable points right now: live lots less any outstanding refund debt. */
    @Transactional(readOnly = true)
    public int balance(String userId) {
        return lotRepository.balance(userId, LocalDateTime.now());
    }

    /** Lots still holding points, soonest expiry first — what the statement counts down. */
    @Transactional(readOnly = true)
    public List<RewardPointsLot> liveLots(String userId) {
        return lotRepository.liveLots(userId, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<RewardPointsTransaction> recentActivity(String userId) {
        return transactionRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
    }

    // ── Buy ─────────────────────────────────────────────────────────────────

    /**
     * Credit points a shop paid for, opening a batch dated a year out like any other.
     *
     * Replay protection lives with the caller, which claims the Razorpay payment id
     * before getting here — one payment credits one batch.
     */
    @Transactional
    public void creditPurchasedPoints(String userId, int points, String reference) {
        requireRetailer(userId);
        creditBatch(userId, points, reference, RewardPointsTransaction.Type.PURCHASED);
        log.info("Points purchased: user={} points={} payment={}", userId, points, reference);
    }

    // ── Earn ────────────────────────────────────────────────────────────────

    /**
     * Credit points earned by a kiosk sale, opening a lot dated a year out.
     *
     * Any outstanding refund debt is settled FIRST, before a new lot is opened — a shop
     * that went negative on a refund earns its way back rather than carrying the
     * shortfall against a fresh, differently-dated batch.
     *
     * Not replay-protected here: the caller only reaches this after winning the unique
     * payment-id insert on the kiosk payment row, so one sale can only arrive once.
     * Silently no-ops for a non-retailer rather than throwing — the walk-in has already
     * paid, and their access must not fail over the shop's account type.
     */
    @Transactional
    public void creditKioskPoints(String userId, int points, String reference) {
        if (points <= 0) {
            return;
        }
        if (!isRetailer(userId)) {
            log.warn("Kiosk sale earned no points: user={} is not a RETAILER (payment={})",
                    userId, reference);
            return;
        }
        creditBatch(userId, points, reference, RewardPointsTransaction.Type.KIOSK_EARNED);
        log.info("Kiosk points earned: user={} points={} payment={}", userId, points, reference);
    }

    /**
     * Open a dated batch, settling any outstanding refund debt first.
     *
     * Shared by earning and buying because the two must behave identically once the
     * points exist: same one-year clock, same debt settlement, same journal shape. A shop
     * that went negative on a refund earns or buys its way back rather than carrying the
     * shortfall behind a fresh, differently-dated batch.
     */
    private void creditBatch(String userId, int points, String reference,
                             RewardPointsTransaction.Type type) {
        LocalDateTime now = LocalDateTime.now();
        int left = points;

        for (RewardPointsLot debt : lotRepository.lockLiveLots(userId, now)) {
            if (left <= 0) break;
            if (!debt.isDebt() || debt.getPointsRemaining() >= 0) continue;
            int settled = Math.min(left, -debt.getPointsRemaining());
            debt.setPointsRemaining(debt.getPointsRemaining() + settled);
            lotRepository.save(debt);
            left -= settled;
            log.info("Refund debt settled from new points: user={} settled={} debtLeft={}",
                    userId, settled, debt.getPointsRemaining());
        }

        if (left > 0) {
            lotRepository.save(RewardPointsLot.builder()
                    .userId(userId)
                    .pointsEarned(left)
                    .pointsRemaining(left)
                    .expiresAt(now.plusDays(pricingService.pointsValidityDays()))
                    .sourceReference(reference)
                    .build());
        }
        journal(userId, points, type, reference);
    }

    /**
     * Take back the points a kiosk sale earned, because it was refunded.
     *
     * Drains that sale's own lot first, then other live lots soonest-expiry first, and
     * carries any shortfall as a debt lot. A shop that already spent the points goes
     * negative and earns its way back: clamping at zero would leave a refunded sale
     * permanently rewarded.
     */
    @Transactional
    public void reverseKioskPoints(String userId, int points, String reference) {
        if (points <= 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<RewardPointsLot> lots = lotRepository.lockLiveLots(userId, now);
        int left = points;

        // Its own lot first — the points most likely still sitting there untouched.
        for (RewardPointsLot lot : lots) {
            if (left <= 0) break;
            if (lot.isDebt() || !reference.equals(lot.getSourceReference())) continue;
            left -= drain(lot, left);
        }
        for (RewardPointsLot lot : lots) {
            if (left <= 0) break;
            if (lot.isDebt() || lot.getPointsRemaining() <= 0) continue;
            left -= drain(lot, left);
        }

        if (left > 0) {
            RewardPointsLot debt = lots.stream().filter(RewardPointsLot::isDebt).findFirst()
                    .orElseGet(() -> RewardPointsLot.builder()
                            .userId(userId).pointsEarned(0).pointsRemaining(0).build());
            debt.setPointsRemaining(debt.getPointsRemaining() - left);
            lotRepository.save(debt);
            log.warn("Kiosk refund left a points shortfall: user={} short={} totalDebt={} payment={} "
                    + "— the points were spent before the refund arrived; it settles against future "
                    + "earnings.", userId, left, debt.getPointsRemaining(), reference);
        }
        journal(userId, -points, RewardPointsTransaction.Type.KIOSK_REVERSED, reference);
        log.info("Kiosk points reversed: user={} points={} payment={}", userId, points, reference);
    }

    // ── Spend ───────────────────────────────────────────────────────────────

    /**
     * Spend points, soonest-expiring lot first so nothing dies that could have been used.
     *
     * Throws {@link QuotaExceededException} (402) when the balance is short, and
     * {@link SecurityException} for a non-retailer. Runs in the CALLER's transaction, so
     * a failure in whatever the points bought rolls the spend back with it.
     */
    @Transactional
    public void spend(String userId, int points, RewardPointsTransaction.Type type, String reference) {
        requireRetailer(userId);
        LocalDateTime now = LocalDateTime.now();
        List<RewardPointsLot> lots = lotRepository.lockLiveLots(userId, now);

        int available = lots.stream().mapToInt(RewardPointsLot::getPointsRemaining).sum();
        if (available < points) {
            throw new QuotaExceededException(
                    "Not enough points (" + available + " left, " + points + " needed). "
                    + "Buy more from your subscription page, earn them through your kiosk "
                    + "link, or pay by card instead.");
        }

        int left = points;
        for (RewardPointsLot lot : lots) {
            if (left <= 0) break;
            if (lot.isDebt() || lot.getPointsRemaining() <= 0) continue;
            left -= drain(lot, left);
        }
        journal(userId, -points, type, reference);
        log.info("Points spent: user={} points={} type={} ref={}", userId, points, type, reference);
    }

    // ── Expiry ──────────────────────────────────────────────────────────────

    /**
     * Write off lots whose year is up. Runs from {@link RewardPointsExpiryJob} after the
     * notices, so a shop is never told "these expire today" by an e-mail that arrives
     * once they already have.
     */
    @Transactional
    public int expireDueLots() {
        LocalDateTime now = LocalDateTime.now();
        List<RewardPointsLot> due = lotRepository.dueForExpiry(now);
        for (RewardPointsLot lot : due) {
            int lost = lot.getPointsRemaining();
            lot.setPointsRemaining(0);
            lot.setExpiredAt(now);
            lotRepository.save(lot);
            journal(lot.getUserId(), -lost, RewardPointsTransaction.Type.EXPIRED, lot.getId());
            log.info("Points expired: user={} points={} lot={}", lot.getUserId(), lost, lot.getId());
        }
        return due.size();
    }

    /** Points a shop will lose on the next expiry date, and when — null when none are dated. */
    @Transactional(readOnly = true)
    public RewardPointsLot nextExpiringLot(String userId) {
        return liveLots(userId).stream().findFirst().orElse(null);
    }

    // ── internals ───────────────────────────────────────────────────────────

    /** Take up to {@code wanted} from a lot; returns how much was actually taken. */
    private int drain(RewardPointsLot lot, int wanted) {
        int taken = Math.min(wanted, Math.max(0, lot.getPointsRemaining()));
        if (taken > 0) {
            lot.setPointsRemaining(lot.getPointsRemaining() - taken);
            lotRepository.save(lot);
        }
        return taken;
    }

    private void journal(String userId, int points, RewardPointsTransaction.Type type, String reference) {
        transactionRepository.save(RewardPointsTransaction.builder()
                .userId(userId).points(points).type(type).reference(reference).build());
    }

    /**
     * Can this account use points at all? The same rule {@link #spend} enforces, asked
     * ahead of time so a UI can decline to offer the rail rather than showing a button
     * that comes back a 403. Says nothing about the balance — an eligible shop with no
     * points still reads true, because "top up" is a real answer for it and is not one
     * for a customer.
     */
    @Transactional(readOnly = true)
    public boolean canSpendPoints(String userId) {
        return isRetailer(userId);
    }

    private boolean isRetailer(String userId) {
        return userRepository.findById(userId).map(User::getRole).orElse(null) == UserRole.RETAILER;
    }

    private void requireRetailer(String userId) {
        if (!isRetailer(userId)) {
            throw new SecurityException(
                    "Points are for paint shops. Everything they buy — extra rooms and reopens — "
                    + "belongs to a shop account.");
        }
    }
}
