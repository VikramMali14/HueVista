package com.gridstore.huevista.project.config;

import com.gridstore.huevista.project.model.RegionCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Keeps the {@code regions_category_check} constraint in sync with the
 * {@link RegionCategory} enum.
 *
 * <p>Hibernate derives this CHECK constraint from the enum when it first creates the table,
 * but with {@code ddl-auto=update} it never alters an existing one. A database created before
 * a value was added keeps a stale constraint that rejects the newer value — so when
 * segmentation began persisting CEILING / DOOR / WINDOW regions, an older database rejected
 * the insert and failed the whole auto-segment run.
 *
 * <p>This drops and recreates the constraint to match the current enum on startup. It is
 * idempotent and best-effort: any failure is logged, never fatal. The values come from
 * {@code RegionCategory.values()} (compile-time constants), so the inlined SQL is injection-safe.
 *
 * <p>Mirrors {@code ImageTypeConstraintReconciler}.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class RegionCategoryConstraintReconciler implements ApplicationRunner {

    private static final String CONSTRAINT = "regions_category_check";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        String allowed = Arrays.stream(RegionCategory.values())
                .map(v -> "'" + v.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            jdbcTemplate.execute("ALTER TABLE regions DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
            jdbcTemplate.execute("ALTER TABLE regions ADD CONSTRAINT " + CONSTRAINT
                    + " CHECK (category IN (" + allowed + "))");
            log.info("[schema] reconciled {} -> ({})", CONSTRAINT, allowed);
        } catch (Exception e) {
            log.warn("[schema] could not reconcile {} ({}). Auto-segmentation may reject "
                    + "CEILING/DOOR/WINDOW regions until this is fixed manually.", CONSTRAINT, e.getMessage());
        }
    }
}
