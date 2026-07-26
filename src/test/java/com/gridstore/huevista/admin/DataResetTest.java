package com.gridstore.huevista.admin;

import com.gridstore.huevista.account.model.Organization;
import com.gridstore.huevista.account.model.OrgType;
import com.gridstore.huevista.account.repository.OrganizationRepository;
import com.gridstore.huevista.auth.model.AuthProvider;
import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.model.UserRole;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.billing.model.Plan;
import com.gridstore.huevista.billing.model.Subscription;
import com.gridstore.huevista.billing.model.SubscriptionStatus;
import com.gridstore.huevista.billing.repository.SubscriptionRepository;
import com.gridstore.huevista.paint.model.Brand;
import com.gridstore.huevista.paint.model.Shade;
import com.gridstore.huevista.paint.repository.BrandRepository;
import com.gridstore.huevista.paint.repository.ShadeRepository;
import com.razorpay.RazorpayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The admin "reset the platform" pass.
 *
 * Deliberately NOT {@code @Transactional}: the service issues TRUNCATE, which commits
 * implicitly on most engines, so a rolled-back test would prove nothing about what
 * actually survives. Each test rebuilds its own fixture instead.
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties")
class DataResetTest {

    @MockitoBean RazorpayClient razorpayClient;

    @Autowired DataResetService service;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository orgRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired BrandRepository brandRepository;
    @Autowired ShadeRepository shadeRepository;
    @Autowired JdbcTemplate jdbc;

    @Value("${app.upload.storage-path}") String storageRoot;

    private String adminId;

    @BeforeEach
    void seed() {
        // TRUNCATE commits, so nothing rolls back between tests — start each one from a
        // genuinely empty database rather than inheriting the previous test's leftovers.
        // H2-only syntax; this fixture never runs anywhere else.
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbc.queryForList("""
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = CURRENT_SCHEMA() AND table_type = 'BASE TABLE'
                    """, String.class)
                    .forEach(t -> jdbc.execute("TRUNCATE TABLE " + t + " RESTART IDENTITY"));
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }

        User admin = userRepository.save(User.builder()
                .email("admin@huevista.test").name("Administrator").password("hashed")
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).emailVerified(true)
                .build());
        adminId = admin.getId();

        User shopOwner = userRepository.save(User.builder()
                .email("shop@huevista.test").name("Shop Owner").password("hashed")
                .provider(AuthProvider.LOCAL).role(UserRole.RETAILER).emailVerified(true)
                .build());
        orgRepository.save(Organization.builder()
                .name("Shop One").slug("shop-one-" + System.nanoTime())
                .type(OrgType.RETAILER).owner(shopOwner)
                .build());
        subscriptionRepository.save(Subscription.builder()
                .user(shopOwner).plan(Plan.PROFESSIONAL).status(SubscriptionStatus.ACTIVE)
                .aiGenerationsLimit(100).aiGenerationsUsed(4)
                .currentPeriodEnd(LocalDateTime.now().plusDays(20))
                .build());

        Brand brand = brandRepository.save(Brand.builder()
                .name("Asian Paints " + System.nanoTime()).slug("ap-" + System.nanoTime())
                .build());
        shadeRepository.save(Shade.builder()
                .brand(brand).name("Ivory").shadeCode("AP-001").hexCode("#FFFFF0")
                .aiDescription("A warm off-white")
                .build());
    }

    @Test
    void wipesShopDataAndKeepsTheCatalogue() {
        assertThat(shadeRepository.count()).isPositive();

        DataResetService.ResetResult result =
                service.resetKeepingCatalogue(adminId, DataResetService.CONFIRM_PHRASE, false);

        // The catalogue is the whole point of this variant — it must survive.
        assertThat(brandRepository.count()).isPositive();
        assertThat(shadeRepository.count()).isPositive();
        assertThat(result.preservedTables()).containsKeys("brands", "shades");

        // Everything a shop owns is gone.
        assertThat(orgRepository.count()).isZero();
        assertThat(subscriptionRepository.count()).isZero();
        assertThat(result.clearedTables()).contains("organizations", "subscriptions", "projects", "regions");
        assertThat(result.totalDeleted()).isPositive();
    }

    @Test
    void keepsTheActingAdminSignedIn() {
        // The JWT carries the user id and JwtAuthFilter resolves it on every request, so
        // losing this row (or gaining a new id) would sign the admin out mid-reset and
        // leave the audit entry pointing at an account that no longer exists.
        service.resetKeepingCatalogue(adminId, DataResetService.CONFIRM_PHRASE, false);

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findById(adminId)).isPresent()
                .get()
                .satisfies(u -> {
                    assertThat(u.getEmail()).isEqualTo("admin@huevista.test");
                    assertThat(u.getRole()).isEqualTo(UserRole.ADMIN);
                    assertThat(u.getPassword()).isEqualTo("hashed");
                    assertThat(u.getDeletedAt()).isNull();
                });
    }

    /**
     * The audit entry must be written with no transaction of ours in progress.
     *
     * {@code AuditService#record} is REQUIRES_NEW: it suspends the caller's transaction
     * and opens a second connection. Called from inside the truncate's transaction it
     * waits for a lock on {@code audit_logs} that the truncate holds and will not
     * release until the request returns — so it blocks forever, and PostgreSQL's
     * deadlock detector never fires because the holder is waiting on application code
     * rather than on a database lock. That froze all 34 tables in production.
     *
     * H2 cannot reproduce the lock (its TRUNCATE commits as it goes), so this asserts
     * the structural property instead: by the time the reset returns, no transaction is
     * active on the calling thread, and the audit row is really there.
     */
    @Test
    void theAuditWriteHappensOutsideTheTruncateTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("precondition: the test itself must not be transactional")
                .isFalse();

        service.resetKeepingCatalogue(adminId, DataResetService.CONFIRM_PHRASE, false);

        // A transaction spanning the whole reset would have wrapped this call too.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'PLATFORM_DATA_RESET'", Long.class))
                .isEqualTo(1);
    }

    @Test
    void theResetIsRecordedInTheFreshAuditLog() {
        // audit_logs is itself cleared, so the entry has to be written afterwards or the
        // only trace of the most destructive action in the product would be erased by it.
        service.resetKeepingCatalogue(adminId, DataResetService.CONFIRM_PHRASE, false);

        Long entries = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'PLATFORM_DATA_RESET'", Long.class);
        assertThat(entries).isEqualTo(1);
    }

    @Test
    void leavesImageFilesAloneUnlessAsked() throws Exception {
        Path stored = writeStoredImage("keep-me.jpg");

        DataResetService.ResetResult result =
                service.resetKeepingCatalogue(adminId, DataResetService.CONFIRM_PHRASE, false);

        assertThat(result.deletedImageFiles()).isZero();
        assertThat(Files.exists(stored)).isTrue();
    }

    @Test
    void purgesImageFilesWhenAsked() throws Exception {
        Path one = writeStoredImage("one.jpg");
        Path two = writeStoredImage("two.png");

        DataResetService.ResetResult result =
                service.resetKeepingCatalogue(adminId, DataResetService.CONFIRM_PHRASE, true);

        assertThat(result.deletedImageFiles()).isGreaterThanOrEqualTo(2);
        assertThat(Files.exists(one)).isFalse();
        assertThat(Files.exists(two)).isFalse();
        // The root survives so the next upload doesn't have to recreate it.
        assertThat(Files.isDirectory(Path.of(storageRoot))).isTrue();
    }

    /** A file under a per-user folder, exactly as {@code LocalStorageService} writes them. */
    private Path writeStoredImage(String name) throws Exception {
        Path target = Path.of(storageRoot, "user-" + adminId, name);
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {1, 2, 3});
        return target;
    }

    @Test
    void refusesWithoutTheExactConfirmationPhrase() {
        assertThatThrownBy(() -> service.resetKeepingCatalogue(adminId, "reset", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(DataResetService.CONFIRM_PHRASE);
        assertThatThrownBy(() -> service.resetKeepingCatalogue(adminId, null, false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(orgRepository.count()).isPositive(); // nothing was touched
    }

    @Test
    void theConfirmationPhraseIsForgivingAboutCaseAndPadding() {
        service.resetKeepingCatalogue(adminId, "  reset all data  ", false);
        assertThat(orgRepository.count()).isZero();
    }

    @Test
    void previewReportsWhatWouldGoWithoutTouchingAnything() {
        DataResetService.ResetResult preview = service.preview();

        assertThat(preview.deletedRows()).containsKey("organizations");
        assertThat(preview.preservedTables()).containsKeys("brands", "shades");
        assertThat(orgRepository.count()).isPositive();
        assertThat(userRepository.count()).isEqualTo(2);
    }

    /**
     * A preserved name that no longer matches a real table silently stops preserving
     * anything — the catalogue would be wiped with everything else and nobody would
     * notice until it was gone.
     */
    @Test
    void everyPreservedTableNameMatchesARealTable() {
        var existing = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = CURRENT_SCHEMA() AND table_type = 'BASE TABLE'
                """, String.class)
                .stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();

        assertThat(existing).contains("brands", "paint_lines", "shades");
        assertThat(service.clearedTableNames())
                .doesNotContain("brands", "paint_lines", "shades", "flyway_schema_history");
    }
}
