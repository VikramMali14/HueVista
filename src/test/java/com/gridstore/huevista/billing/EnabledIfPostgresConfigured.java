package com.gridstore.huevista.billing;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the annotated test only when {@code huevista.test.postgres.url} names a database.
 *
 * <p>Most of the suite is happy on H2 and must stay runnable with nothing installed.
 * The queries H2 cannot vouch for are the exception, and they need a real PostgreSQL
 * rather than a dialect setting.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@EnabledIfSystemProperty(named = "huevista.test.postgres.url", matches = ".*\\S.*")
public @interface EnabledIfPostgresConfigured {
}
