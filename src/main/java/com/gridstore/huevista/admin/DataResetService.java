package com.gridstore.huevista.admin;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Empties the platform of shop and customer data while keeping the paint catalogue.
 *
 * This is the "start the season fresh" pass: every account, organization, project,
 * region, subscription, wallet, payment and access code goes; brands, product lines
 * and shades stay. The shade catalogue in particular is irreplaceable — it is
 * uploaded by hand through the admin importer and AI-enriched once at seed time,
 * with no copy in the repository — so it is preserved unconditionally and this
 * service offers no way to include it.
 *
 * <h2>What is never touched</h2>
 * <ul>
 *   <li>{@code flyway_schema_history} — clearing it makes Flyway try to replay every
 *       migration over a schema that already has them, and the app stops booting.</li>
 *   <li>{@code brands}, {@code paint_lines}, {@code shades} — the catalogue.</li>
 *   <li>The calling admin's own {@code users} row, restored with its original id (see
 *       {@link #restore}) so the reset does not sign the operator out mid-flight.</li>
 * </ul>
 *
 * <h2>Why the table list is discovered, not hard-coded</h2>
 * A hard-coded list silently rots: add an entity, forget the list, and its rows
 * survive a "full reset" pointing at owners that no longer exist. Reading the live
 * schema instead means a new table is cleared by default and only the explicitly
 * {@link #PRESERVED} ones survive — the safe direction to fail in. {@code DataResetTest}
 * pins the resulting split so adding a table is a conscious decision, not a surprise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataResetService {

    /** Typed by the admin to arm the reset. Compared case-insensitively, trimmed. */
    public static final String CONFIRM_PHRASE = "RESET ALL DATA";

    /**
     * Tables the reset must never empty. Lower-case; the schema is compared
     * case-insensitively because H2 (tests) and PostgreSQL (production) disagree on
     * identifier folding.
     */
    static final Set<String> PRESERVED = Set.of(
            "flyway_schema_history",
            "brands",
            "paint_lines",
            "shades");

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final AuditService auditService;

    /**
     * @param clearedTables  every table emptied, alphabetically
     * @param preservedTables the catalogue tables deliberately left alone, with their row counts
     * @param deletedRows    rows removed per table, only for tables that actually had any
     * @param totalDeleted   sum of {@code deletedRows}
     */
    public record ResetResult(List<String> clearedTables,
                              Map<String, Long> preservedTables,
                              Map<String, Long> deletedRows,
                              long totalDeleted) {}

    /**
     * Wipe everything but the catalogue.
     *
     * @param adminUserId the signed-in admin, whose own account is preserved
     * @param confirmation must equal {@link #CONFIRM_PHRASE}
     * @throws IllegalArgumentException if the confirmation phrase does not match
     */
    @Transactional
    public ResetResult resetKeepingCatalogue(String adminUserId, String confirmation) {
        if (confirmation == null
                || !confirmation.trim().equalsIgnoreCase(CONFIRM_PHRASE)) {
            throw new IllegalArgumentException(
                    "Type \"" + CONFIRM_PHRASE + "\" exactly to confirm the reset.");
        }
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin account not found: " + adminUserId));

        List<String> tables = tablesToClear();
        Map<String, Long> deletedRows = countRows(tables);
        long totalDeleted = deletedRows.values().stream().mapToLong(Long::longValue).sum();

        log.warn("[admin] DATA RESET starting: admin={} tables={} rows={}",
                adminUserId, tables.size(), totalDeleted);
        truncate(tables);
        restore(admin);

        // Recorded AFTER the truncate on purpose: audit_logs is one of the cleared
        // tables, so writing it first would erase the only trace of the reset.
        auditService.record(adminUserId, "PLATFORM_DATA_RESET", "PLATFORM", "all",
                "cleared=" + tables.size() + " tables rows=" + totalDeleted
                + " preserved=" + String.join(",", PRESERVED));
        log.warn("[admin] DATA RESET complete: {} rows removed from {} tables, catalogue kept",
                totalDeleted, tables.size());

        return new ResetResult(tables, countRows(catalogueTables()), deletedRows, totalDeleted);
    }

    /** A preview for the confirmation screen — what would go, and what would stay. */
    @Transactional(readOnly = true)
    public ResetResult preview() {
        List<String> tables = tablesToClear();
        Map<String, Long> rows = countRows(tables);
        return new ResetResult(tables, countRows(catalogueTables()), rows,
                rows.values().stream().mapToLong(Long::longValue).sum());
    }

    /** Every base table in the current schema except {@link #PRESERVED}, alphabetically. */
    private List<String> tablesToClear() {
        return allTables().stream().filter(t -> !PRESERVED.contains(t)).toList();
    }

    private List<String> catalogueTables() {
        return allTables().stream()
                .filter(PRESERVED::contains)
                .filter(t -> !t.equals("flyway_schema_history"))
                .toList();
    }

    private List<String> allTables() {
        List<String> names = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = CURRENT_SCHEMA()
                   AND table_type = 'BASE TABLE'
                 ORDER BY table_name
                """, String.class);
        return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
    }

    private Map<String, Long> countRows(List<String> tables) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
            if (n != null && n > 0) {
                counts.put(table, n);
            }
        }
        return counts;
    }

    /**
     * Empty the tables in one shot.
     *
     * PostgreSQL takes every table in a single {@code TRUNCATE}, which sidesteps
     * foreign-key ordering entirely — a table may be truncated as long as everything
     * referencing it is in the same statement, and here everything is. H2 (tests only)
     * has no multi-table form, so referential integrity comes off for the batch.
     */
    private void truncate(List<String> tables) {
        if (tables.isEmpty()) return;
        if (isPostgres()) {
            jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY");
            return;
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            tables.forEach(t -> jdbc.execute("TRUNCATE TABLE " + t + " RESTART IDENTITY"));
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    private boolean isPostgres() {
        try {
            String product = jdbc.execute(
                    (org.springframework.jdbc.core.ConnectionCallback<String>) c ->
                            c.getMetaData().getDatabaseProductName());
            return product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
        } catch (Exception e) {
            log.warn("[admin] could not read the database product name ({}); "
                     + "assuming PostgreSQL for the reset", e.getMessage());
            return true;
        }
    }

    /**
     * Put the acting admin back, keeping the ORIGINAL id.
     *
     * The JWT carries the user id and {@code JwtAuthFilter} resolves it on every
     * request, so a new id would log the admin out the instant the reset finished and
     * leave the audit entry pointing at nobody. Written as a plain INSERT rather than
     * a JPA save because the row must land with an assigned id despite the entity's
     * UUID generator.
     */
    private void restore(User admin) {
        jdbc.update("""
                INSERT INTO users (id, email, password, name, picture, provider, provider_id,
                                   role, email_verified, failed_login_attempts, phone_number,
                                   phone_verified, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                admin.getId(),
                admin.getEmail(),
                admin.getPassword(),
                admin.getName(),
                admin.getPicture(),
                admin.getProvider() == null ? null : admin.getProvider().name(),
                admin.getProviderId(),
                admin.getRole() == null ? null : admin.getRole().name(),
                admin.isEmailVerified(),
                0,
                admin.getPhoneNumber(),
                admin.isPhoneVerified(),
                admin.getCreatedAt() == null ? LocalDateTime.now() : admin.getCreatedAt(),
                LocalDateTime.now());
        log.warn("[admin] restored acting admin account {} after the reset", admin.getEmail());
    }

    /** Tables cleared, as a plain list — used by the confirmation screen and tests. */
    public List<String> clearedTableNames() {
        return new ArrayList<>(tablesToClear());
    }
}
