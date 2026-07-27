package com.gridstore.huevista.project.service;

import com.gridstore.huevista.account.model.CustomerAccessCode;
import com.gridstore.huevista.account.repository.OrgMembershipRepository;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.service.BillingService;
import com.gridstore.huevista.billing.service.PricingService;
import com.gridstore.huevista.common.exception.SubscriptionRequiredException;
import com.gridstore.huevista.project.model.Project;
import com.gridstore.huevista.project.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may work on a project, and what happens to a paid validity window when the owner's
 * subscription comes and goes.
 *
 * The pause/resume rule is the subtle one, and the reason the window stores a REMAINDER
 * rather than a frozen end date: someone who buys 30 days and then subscribes for six
 * months must still have 30 days waiting when the plan ends, not a window that quietly
 * expired in month one.
 */
class ProjectAccessServiceTest {

    private static final int REOPEN_PAISE = 900;

    private BillingService billing;
    private PricingService pricing;
    private ProjectAccessService access;

    @BeforeEach
    void setUp() {
        billing = mock(BillingService.class);
        pricing = new PricingService(billing, mock(OrgMembershipRepository.class));
        ReflectionTestUtils.setField(pricing, "projectReopenPaise", REOPEN_PAISE);
        ReflectionTestUtils.setField(pricing, "projectValidDays", 30);
        access = new ProjectAccessService(mock(ProjectRepository.class), billing, pricing);
        when(billing.findEntitlingSubscription(any())).thenReturn(Optional.empty());
    }

    private void subscribed(boolean yes) {
        when(billing.findEntitlingSubscription(any()))
                .thenReturn(yes ? Optional.of(new Subscription()) : Optional.empty());
    }

    // ─── Who gets FULL access ────────────────────────────────────────────────

    @Test
    void aSubscribedRetailerCanWorkOnEverything() {
        subscribed(true);
        Project plain = Project.builder().id("p").build();
        assertThat(access.accessFor("shop-1", UserRole.RETAILER, plain).editable()).isTrue();
    }

    /**
     * The lapsed-shop rule: the room is still there and still shows the colours that were
     * last applied — the shop simply cannot change them. Hiding the work outright is what
     * this deliberately does NOT do.
     */
    @Test
    void aLapsedRetailerSeesTheProjectButCannotChangeIt() {
        subscribed(false);
        Project plain = Project.builder().id("p").build();
        var result = access.accessFor("shop-1", UserRole.RETAILER, plain);

        assertThat(result.editable()).isFalse();
        assertThat(result.mode()).isEqualTo(ProjectAccessService.Mode.VIEW_ONLY);
        assertThat(result.reason()).contains("view-only").contains("Subscribe");
        assertThatThrownBy(() -> access.assertEditable("shop-1", UserRole.RETAILER, plain))
                .isInstanceOf(SubscriptionRequiredException.class);
    }

    /**
     * The shop paid for this work when it issued the code. Whether the customer holding it
     * has a subscription is irrelevant — a customer cannot even buy one.
     */
    @Test
    void workUnderAShopCodeIsNeverGatedOnASubscription() {
        subscribed(false);
        Project fromCode = Project.builder()
                .id("p")
                .accessCode(CustomerAccessCode.builder().id("code-1").build())
                .build();
        assertThat(access.accessFor("cust-1", UserRole.CUSTOMER, fromCode).editable()).isTrue();
    }

    @Test
    void anOpenPaidWindowGrantsFullAccessWithoutASubscription() {
        subscribed(false);
        Project bought = Project.builder()
                .id("p")
                .accessExpiresAt(LocalDateTime.now().plusDays(12))
                .build();
        assertThat(access.accessFor("user-1", UserRole.CUSTOMER, bought).editable()).isTrue();
    }

    @Test
    void aLapsedWindowGivesViewOnlyAndNamesTheReopenPrice() {
        subscribed(false);
        Project lapsed = Project.builder()
                .id("p")
                .accessExpiresAt(LocalDateTime.now().minusHours(1))
                .build();
        var result = access.accessFor("user-1", UserRole.CUSTOMER, lapsed);

        assertThat(result.editable()).isFalse();
        assertThat(result.reason()).contains("validity has ended");
        assertThat(result.reopenPricePaise()).isEqualTo(REOPEN_PAISE);
    }

    // ─── Pause / resume ──────────────────────────────────────────────────────

    @Test
    void subscribingPausesTheWindowAndBanksWhatIsLeft() {
        LocalDateTime now = LocalDateTime.now();
        Project bought = Project.builder()
                .id("p")
                .accessExpiresAt(now.plusDays(20))
                .build();

        assertThat(access.reconcile(bought, true, now)).isTrue();
        assertThat(bought.getAccessPausedAt()).isEqualTo(now);
        assertThat(bought.getAccessExpiresAt()).isNull();
        assertThat(bought.getAccessRemainingSeconds())
                .isEqualTo(Duration.ofDays(20).toSeconds());
        // Paused reads as open — the subscription is what is granting access meanwhile.
        assertThat(bought.isAccessWindowOpen()).isTrue();
    }

    @Test
    void theSubscriptionEndingResumesTheWindowFromWhereItStopped() {
        LocalDateTime now = LocalDateTime.now();
        Project banked = Project.builder()
                .id("p")
                .accessPausedAt(now.minusMonths(6))
                .accessRemainingSeconds(Duration.ofDays(20).toSeconds())
                .build();

        assertThat(access.reconcile(banked, false, now)).isTrue();
        assertThat(banked.getAccessPausedAt()).isNull();
        assertThat(banked.getAccessRemainingSeconds()).isNull();
        // All 20 days are still there six months later — the whole point of pausing.
        assertThat(banked.getAccessExpiresAt()).isEqualTo(now.plusDays(20));
    }

    /**
     * Reconciliation runs on every read, so running it twice in the same subscription
     * state must change nothing. Storing a frozen end date instead of a remainder is what
     * would break this — each pass would re-park a shorter window.
     */
    @Test
    void reconcilingTwiceInTheSameStateChangesNothing() {
        LocalDateTime now = LocalDateTime.now();
        Project bought = Project.builder().id("p").accessExpiresAt(now.plusDays(20)).build();

        assertThat(access.reconcile(bought, true, now)).isTrue();
        long parked = bought.getAccessRemainingSeconds();
        assertThat(access.reconcile(bought, true, now.plusDays(3))).isFalse();
        assertThat(bought.getAccessRemainingSeconds()).isEqualTo(parked);
    }

    @Test
    void aProjectWithNoWindowIsNeverTouched() {
        Project plain = Project.builder().id("p").build();
        assertThat(access.reconcile(plain, true, LocalDateTime.now())).isFalse();
        assertThat(access.reconcile(plain, false, LocalDateTime.now())).isFalse();
        assertThat(plain.hasAccessWindow()).isFalse();
    }

    /** A window that ran out while paused resumes already lapsed rather than reviving. */
    @Test
    void aPausedWindowWithNothingLeftResumesExpired() {
        LocalDateTime now = LocalDateTime.now();
        Project spent = Project.builder()
                .id("p")
                .accessPausedAt(now.minusDays(1))
                .accessRemainingSeconds(0L)
                .build();

        access.reconcile(spent, false, now);
        assertThat(spent.isAccessWindowOpen()).isFalse();
    }

    // ─── Opening and extending ───────────────────────────────────────────────

    @Test
    void buyingWhileSubscribedBanksTheDaysInsteadOfBurningThem() {
        Project bought = Project.builder().id("p").build();
        access.openWindow(bought, 30, 5000, true);

        assertThat(bought.getAccessPausedAt()).isNotNull();
        assertThat(bought.getAccessExpiresAt()).isNull();
        assertThat(bought.getAccessRemainingSeconds()).isEqualTo(Duration.ofDays(30).toSeconds());
        assertThat(bought.getPurchasePricePaise()).isEqualTo(5000);
    }

    @Test
    void buyingWithoutASubscriptionStartsTheClockImmediately() {
        Project bought = Project.builder().id("p").build();
        access.openWindow(bought, 30, 9900, false);

        assertThat(bought.getAccessPausedAt()).isNull();
        assertThat(bought.getAccessExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
        assertThat(bought.getPurchasePricePaise()).isEqualTo(9900);
    }

    /** Reopening early must not destroy days already paid for. */
    @Test
    void extendingAWindowThatIsStillRunningAddsToIt() {
        LocalDateTime end = LocalDateTime.now().plusDays(5);
        Project open = Project.builder().id("p").accessExpiresAt(end).build();

        access.extendWindow(open, 30);
        assertThat(open.getAccessExpiresAt()).isEqualTo(end.plusDays(30));
    }

    @Test
    void extendingALapsedWindowRestartsFromNowNotFromTheOldExpiry() {
        Project lapsed = Project.builder()
                .id("p")
                .accessExpiresAt(LocalDateTime.now().minusDays(10))
                .build();

        access.extendWindow(lapsed, 30);
        assertThat(lapsed.getAccessExpiresAt())
                .isAfter(LocalDateTime.now().plusDays(29))
                .isBefore(LocalDateTime.now().plusDays(31));
    }

    @Test
    void extendingAPausedWindowGrowsTheBankedRemainder() {
        Project paused = Project.builder()
                .id("p")
                .accessPausedAt(LocalDateTime.now())
                .accessRemainingSeconds(Duration.ofDays(5).toSeconds())
                .build();

        access.extendWindow(paused, 30);
        assertThat(paused.getAccessRemainingSeconds()).isEqualTo(Duration.ofDays(35).toSeconds());
        assertThat(paused.getAccessPausedAt()).isNotNull();
    }
}
