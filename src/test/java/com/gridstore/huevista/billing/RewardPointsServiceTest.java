package com.gridstore.huevista.billing;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.RewardPointsLot;
import com.gridstore.huevista.billing.model.RewardPointsTransaction;
import com.gridstore.huevista.billing.repository.RewardPointsLotRepository;
import com.gridstore.huevista.billing.repository.RewardPointsTransactionRepository;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.billing.service.RewardPointsService;
import com.gridstore.huevista.common.exception.QuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The point ledger's rules: points age in dated batches, spending drains the batch
 * closest to dying, a refund can push a shop negative, and only shops hold points at all.
 */
class RewardPointsServiceTest {

    private static final String SHOP = "shop-1";
    private static final int VALIDITY_DAYS = 365;

    private RewardPointsLotRepository lots;
    private RewardPointsTransactionRepository txns;
    private UserRepository users;
    private PricingService pricing;
    private RewardPointsService svc;

    /** Lots the fake repository hands back, in the order the real query would. */
    private final List<RewardPointsLot> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lots = mock(RewardPointsLotRepository.class);
        txns = mock(RewardPointsTransactionRepository.class);
        users = mock(UserRepository.class);
        pricing = mock(PricingService.class);
        svc = new RewardPointsService(lots, txns, users, pricing);

        when(pricing.pointsValidityDays()).thenReturn(VALIDITY_DAYS);
        when(users.findById(SHOP)).thenReturn(Optional.of(
                User.builder().id(SHOP).email("shop@example.com").role(UserRole.RETAILER).build()));
        when(lots.lockLiveLots(anyString(), any())).thenAnswer(inv -> stored);
        when(lots.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RewardPointsLot lot(int remaining, LocalDateTime expiresAt, String ref) {
        return RewardPointsLot.builder()
                .id("lot-" + stored.size()).userId(SHOP)
                .pointsEarned(Math.max(remaining, 0)).pointsRemaining(remaining)
                .expiresAt(expiresAt).sourceReference(ref)
                .build();
    }

    // ── Earning ─────────────────────────────────────────────────────────────

    @Test
    void earnedPointsOpenALotDatedAYearOut() {
        svc.creditKioskPoints(SHOP, 30, "pay_1");

        ArgumentCaptor<RewardPointsLot> saved = ArgumentCaptor.forClass(RewardPointsLot.class);
        verify(lots).save(saved.capture());
        assertThat(saved.getValue().getPointsRemaining()).isEqualTo(30);
        assertThat(saved.getValue().getSourceReference()).isEqualTo("pay_1");
        assertThat(saved.getValue().getExpiresAt())
                .isCloseTo(LocalDateTime.now().plusDays(VALIDITY_DAYS),
                        within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    /**
     * A shop that went negative on a refund earns its way back before opening a fresh,
     * differently-dated batch — otherwise the debt would sit behind newer points and the
     * shop would look richer than it is.
     */
    @Test
    void newPointsSettleAnOutstandingRefundDebtFirst() {
        stored.add(lot(-20, null, null));   // debt: no expiry

        svc.creditKioskPoints(SHOP, 30, "pay_2");

        assertThat(stored.get(0).getPointsRemaining()).isZero();
        ArgumentCaptor<RewardPointsLot> saved = ArgumentCaptor.forClass(RewardPointsLot.class);
        verify(lots, org.mockito.Mockito.atLeast(2)).save(saved.capture());
        RewardPointsLot fresh = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(fresh.getPointsRemaining()).isEqualTo(10);  // 30 earned - 20 owed
    }

    /** Points are a shop thing. A customer paying at a kiosk must not accrue them. */
    @Test
    void onlyRetailersEarnPoints() {
        when(users.findById(SHOP)).thenReturn(Optional.of(
                User.builder().id(SHOP).role(UserRole.CUSTOMER).build()));

        svc.creditKioskPoints(SHOP, 30, "pay_1");

        verify(lots, never()).save(any());
    }

    // ── Spending ────────────────────────────────────────────────────────────

    @Test
    void spendingDrainsTheSoonestExpiringBatchFirst() {
        RewardPointsLot dyingSoon = lot(25, LocalDateTime.now().plusDays(5), "pay_1");
        RewardPointsLot fresh = lot(40, LocalDateTime.now().plusDays(300), "pay_2");
        stored.add(dyingSoon);
        stored.add(fresh);

        svc.spend(SHOP, 40, RewardPointsTransaction.Type.SPENT_ON_IMAGE, null);

        // The batch about to die is emptied before the healthy one is touched.
        assertThat(dyingSoon.getPointsRemaining()).isZero();
        assertThat(fresh.getPointsRemaining()).isEqualTo(25);
    }

    @Test
    void spendingMoreThanTheBalanceIsRefusedAndTouchesNothing() {
        RewardPointsLot only = lot(30, LocalDateTime.now().plusDays(100), "pay_1");
        stored.add(only);

        assertThatThrownBy(() -> svc.spend(SHOP, 40, RewardPointsTransaction.Type.SPENT_ON_IMAGE, null))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("Not enough points");
        assertThat(only.getPointsRemaining()).isEqualTo(30);
        verify(txns, never()).save(any());
    }

    /** Debt counts against the balance, so a shop in the red cannot spend its way further. */
    @Test
    void outstandingDebtReducesWhatCanBeSpent() {
        stored.add(lot(50, LocalDateTime.now().plusDays(100), "pay_1"));
        stored.add(lot(-20, null, null));

        // 50 earned less 20 owed = 30 spendable, so an image at 40 is out of reach.
        assertThatThrownBy(() -> svc.spend(SHOP, 40, RewardPointsTransaction.Type.SPENT_ON_IMAGE, null))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void nonRetailersCannotSpendPoints() {
        when(users.findById(SHOP)).thenReturn(Optional.of(
                User.builder().id(SHOP).role(UserRole.PAINTER).build()));

        assertThatThrownBy(() -> svc.spend(SHOP, 9, RewardPointsTransaction.Type.SPENT_ON_PROJECT_REOPEN, null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("paint shops");
    }

    /**
     * The same rule, asked before anything is spent. This is what a UI reads to decide
     * whether to offer the points rail at all — a customer shown that button can only ever
     * be refused by {@link #nonRetailersCannotSpendPoints}, so the two must agree.
     */
    @Test
    void eligibilityMatchesWhoIsAllowedToSpend() {
        assertThat(svc.canSpendPoints(SHOP)).isTrue();  // RETAILER, per setUp

        when(users.findById(SHOP)).thenReturn(Optional.of(
                User.builder().id(SHOP).role(UserRole.CUSTOMER).build()));
        assertThat(svc.canSpendPoints(SHOP)).isFalse();
    }

    /** Eligibility is about the ROLE, not the wallet: a shop with nothing left can top up. */
    @Test
    void aShopWithAnEmptyBalanceIsStillEligible() {
        assertThat(svc.balance(SHOP)).isZero();
        assertThat(svc.canSpendPoints(SHOP)).isTrue();
    }

    // ── Refunds ─────────────────────────────────────────────────────────────

    @Test
    void aRefundTakesBackTheSalesOwnBatchFirst() {
        RewardPointsLot other = lot(30, LocalDateTime.now().plusDays(10), "pay_other");
        RewardPointsLot theirs = lot(30, LocalDateTime.now().plusDays(300), "pay_1");
        stored.add(other);
        stored.add(theirs);

        svc.reverseKioskPoints(SHOP, 30, "pay_1");

        // Even though `other` expires sooner, the refunded sale's own points go back.
        assertThat(theirs.getPointsRemaining()).isZero();
        assertThat(other.getPointsRemaining()).isEqualTo(30);
    }

    /**
     * Spending the points before the refund lands must not leave the sale rewarded.
     * The shortfall becomes debt and settles against future earnings.
     */
    @Test
    void aRefundAfterThePointsWereSpentLeavesDebt() {
        stored.add(lot(10, LocalDateTime.now().plusDays(300), "pay_1"));

        svc.reverseKioskPoints(SHOP, 30, "pay_1");

        ArgumentCaptor<RewardPointsLot> saved = ArgumentCaptor.forClass(RewardPointsLot.class);
        verify(lots, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        RewardPointsLot debt = saved.getAllValues().stream()
                .filter(RewardPointsLot::isDebt).findFirst().orElseThrow();
        assertThat(debt.getPointsRemaining()).isEqualTo(-20);   // 30 owed, only 10 left
        assertThat(debt.getExpiresAt()).isNull();               // debt must not age away
    }

    // ── Expiry ──────────────────────────────────────────────────────────────

    @Test
    void theSweepZeroesDueLotsAndJournalsWhatWasLost() {
        RewardPointsLot stale = lot(25, LocalDateTime.now().minusMinutes(1), "pay_1");
        when(lots.dueForExpiry(any())).thenReturn(List.of(stale));

        assertThat(svc.expireDueLots()).isEqualTo(1);

        assertThat(stale.getPointsRemaining()).isZero();
        assertThat(stale.getExpiredAt()).isNotNull();
        ArgumentCaptor<RewardPointsTransaction> txn =
                ArgumentCaptor.forClass(RewardPointsTransaction.class);
        verify(txns).save(txn.capture());
        assertThat(txn.getValue().getPoints()).isEqualTo(-25);
        assertThat(txn.getValue().getType()).isEqualTo(RewardPointsTransaction.Type.EXPIRED);
    }

    private static org.assertj.core.data.TemporalUnitOffset within(
            long amount, java.time.temporal.TemporalUnit unit) {
        return new org.assertj.core.data.TemporalUnitWithinOffset(amount, unit);
    }
}
