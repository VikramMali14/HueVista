package com.gridstore.huevista.admin;

import com.gridstore.huevista.auth.model.User;
import com.gridstore.huevista.auth.repository.UserRepository;
import com.gridstore.huevista.common.audit.AuditService;
import com.gridstore.huevista.common.exception.ResourceNotFoundException;
import com.gridstore.huevista.image.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 *   <li>The calling admin's own {@code users} row, restored with its original id so the
 *       reset does not sign the operator out mid-flight.</li>
 * </ul>
 *
 * <h2>Why the table list is discovered, not hard-coded</h2>
 * A hard-coded list silently rots: add an entity, forget the list, and its rows
 * survive a "full reset" pointing at owners that no longer exist. Reading the live
 * schema instead means a new table is cleared by default and only the explicitly
 * {@link #PRESERVED} ones survive — the safe direction to fail in. {@code DataResetTest}
 * pins the resulting split so adding a table is a conscious decision, not a surprise.
 *
 * <h2>Ordering</h2>
 * Only the truncate is transactional (see {@link DataResetTransaction}). The audit
 * entry and the image purge both run afterwards, once the exclusive table locks the
 * truncate takes have been released — doing either inside deadlocked the reset in
 * production.
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
    private final StorageService storageService;
    private final DataResetTransaction resetTransaction;

    /**
     * @param clearedTables  every table emptied, alphabetically
     * @param preservedTables the catalogue tables deliberately left alone, with their row counts
     * @param deletedRows    rows removed per table, only for tables that actually had any
     * @param totalDeleted   sum of {@code deletedRows}
     * @param deletedImageFiles files removed from the image store; always 0 on a preview
     */
    public record ResetResult(List<String> clearedTables,
                              Map<String, Long> preservedTables,
                              Map<String, Long> deletedRows,
                              long totalDeleted,
                              int deletedImageFiles) {}

    /**
     * Wipe everything but the catalogue.
     *
     * Deliberately NOT {@code @Transactional} — see the class note. The truncate gets its
     * own short transaction; the audit write and the image purge follow it.
     *
     * @param adminUserId the signed-in admin, whose own account is preserved
     * @param confirmation must equal {@link #CONFIRM_PHRASE}
     * @param deleteImageFiles also purge the image store (S3 bucket or upload directory).
     *                         Separate from the row deletion because it reaches outside
     *                         the database, and because the rows can be restored from a
     *                         snapshot while the files cannot.
     * @throws IllegalArgumentException if the confirmation phrase does not match
     */
    public ResetResult resetKeepingCatalogue(String adminUserId, String confirmation,
                                             boolean deleteImageFiles) {
        if (confirmation == null || !confirmation.trim().equalsIgnoreCase(CONFIRM_PHRASE)) {
            throw new IllegalArgumentException(
                    "Type \"" + CONFIRM_PHRASE + "\" exactly to confirm the reset.");
        }
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin account not found: " + adminUserId));

        List<String> tables = tablesToClear();
        Map<String, Long> deletedRows = countRows(tables);
        long totalDeleted = deletedRows.values().stream().mapToLong(Long::longValue).sum();

        log.warn("[admin] DATA RESET starting: admin={} tables={} rows={} images={}",
                adminUserId, tables.size(), totalDeleted, deleteImageFiles);
        resetTransaction.wipe(tables, admin);

        // Everything below runs with the truncate committed and its locks released.
        int deletedFiles = deleteImageFiles ? purgeImageFiles() : 0;

        // Recorded AFTER the truncate on purpose: audit_logs is one of the cleared
        // tables, so writing it first would erase the only trace of the reset.
        auditService.record(adminUserId, "PLATFORM_DATA_RESET", "PLATFORM", "all",
                "cleared=" + tables.size() + " tables rows=" + totalDeleted
                + " imageFiles=" + deletedFiles
                + " preserved=" + String.join(",", PRESERVED));
        log.warn("[admin] DATA RESET complete: {} rows from {} tables, {} image file(s), catalogue kept",
                totalDeleted, tables.size(), deletedFiles);

        return new ResetResult(tables, countRows(catalogueTables()), deletedRows, totalDeleted, deletedFiles);
    }

    /**
     * Never throws: the rows are already gone by the time this runs, so reporting a
     * failure here would describe a reset that did in fact happen and invite a second
     * run. Logs loudly and reports zero instead.
     */
    private int purgeImageFiles() {
        try {
            return storageService.deleteAll();
        } catch (Exception e) {
            log.error("[admin] image purge failed after the data reset — the database is "
                      + "already clear, so these files are orphaned and must be removed "
                      + "by hand: {}", e.getMessage());
            return 0;
        }
    }

    /** A preview for the confirmation screen — what would go, and what would stay. */
    @Transactional(readOnly = true)
    public ResetResult preview() {
        List<String> tables = tablesToClear();
        Map<String, Long> rows = countRows(tables);
        return new ResetResult(tables, countRows(catalogueTables()), rows,
                rows.values().stream().mapToLong(Long::longValue).sum(), 0);
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

    /** Tables cleared, as a plain list — used by the confirmation screen and tests. */
    public List<String> clearedTableNames() {
        return new ArrayList<>(tablesToClear());
    }
}
