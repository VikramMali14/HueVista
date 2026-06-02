package com.gridstore.huevista.image.config;

import com.gridstore.huevista.image.model.ImageType;
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
 * Keeps the {@code uploaded_images_image_type_check} constraint in sync with the
 * {@link ImageType} enum.
 *
 * <p>Hibernate derives this CHECK constraint from the enum when it first creates the table,
 * but with {@code ddl-auto=update} it never alters an existing one. A database created before
 * a value was added keeps a stale constraint that rejects the newer value — and the upload
 * flow legitimately persists {@link ImageType#UNKNOWN} when Claude Vision is unavailable, so a
 * stale constraint turns a graceful-degradation upload into a 500.
 *
 * <p>This drops and recreates the constraint to match the current enum on startup. It is
 * idempotent and best-effort: any failure is logged, never fatal. The values come from
 * {@code ImageType.values()} (compile-time constants), so the inlined SQL is injection-safe.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ImageTypeConstraintReconciler implements ApplicationRunner {

    private static final String CONSTRAINT = "uploaded_images_image_type_check";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        String allowed = Arrays.stream(ImageType.values())
                .map(v -> "'" + v.name() + "'")
                .collect(Collectors.joining(", "));
        try {
            jdbcTemplate.execute("ALTER TABLE uploaded_images DROP CONSTRAINT IF EXISTS " + CONSTRAINT);
            jdbcTemplate.execute("ALTER TABLE uploaded_images ADD CONSTRAINT " + CONSTRAINT
                    + " CHECK (image_type IN (" + allowed + "))");
            log.info("[schema] reconciled {} -> ({})", CONSTRAINT, allowed);
        } catch (Exception e) {
            log.warn("[schema] could not reconcile {} ({}). A genuine Claude Vision outage may "
                    + "reject UNKNOWN-typed uploads until this is fixed manually.", CONSTRAINT, e.getMessage());
        }
    }
}
