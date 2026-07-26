package com.gridstore.huevista.admin;

import com.gridstore.huevista.auth.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * The transactional core of the platform reset — and <em>only</em> that.
 *
 * It is a separate bean so the transaction boundary is the truncate and nothing else.
 * TRUNCATE takes an ACCESS EXCLUSIVE lock on every table it names and holds it until
 * commit, which makes anything else done inside that transaction dangerous in two
 * distinct ways:
 *
 * <ul>
 *   <li><b>Deadlock.</b> Writing the audit entry from inside deadlocked the reset in
 *       production. {@code AuditService#record} is {@code REQUIRES_NEW}, so it suspends
 *       the outer transaction and opens a second connection, which then waits for a
 *       lock on {@code audit_logs} that the first transaction holds and will not release
 *       until the request returns. PostgreSQL's deadlock detector never fires — the
 *       holder is not itself waiting on a database lock, it is waiting on application
 *       code — so it simply blocks forever, freezing every one of the 34 tables.</li>
 *   <li><b>Lock duration.</b> Purging the image store means thousands of network calls
 *       to S3. Doing that before commit would hold the platform's tables under an
 *       exclusive lock for the length of the purge.</li>
 * </ul>
 *
 * So the audit write and the file purge both happen in {@link DataResetService} after
 * this returns and the locks are gone. What stays inside is the part that genuinely
 * needs to be atomic: emptying the tables and putting the acting admin back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class DataResetTransaction {

    private final JdbcTemplate jdbc;

    /**
     * Empty {@code tables} and restore {@code admin}, atomically.
     *
     * Atomic on purpose: a truncate that succeeded alongside a failed admin restore
     * would leave a platform nobody can sign in to administer.
     */
    @Transactional
    void wipe(List<String> tables, User admin) {
        if (tables.isEmpty()) return;
        truncate(tables);
        restore(admin);
    }

    /**
     * PostgreSQL takes every table in a single statement, which sidesteps foreign-key
     * ordering entirely — a table may be truncated as long as everything referencing it
     * is named alongside it, and here everything is. H2 (tests only) has no multi-table
     * form, so referential integrity comes off for the batch.
     */
    private void truncate(List<String> tables) {
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
}
