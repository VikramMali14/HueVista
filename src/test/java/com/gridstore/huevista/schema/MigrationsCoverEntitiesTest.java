package com.gridstore.huevista.schema;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every persistent field must have a Flyway migration behind it.
 *
 * Production runs {@code ddl-auto=validate} against a Flyway-managed schema; the test
 * suite runs {@code create-drop} on H2 with Flyway switched off (the migrations are
 * PostgreSQL-specific and won't replay on H2). That gap is silent and total: adding a
 * column to an entity without writing the migration passes every test and then
 * crash-loops the deployed app on boot with
 *
 *   SchemaManagementException: Schema validation: missing column [x] in table [y]
 *
 * which is exactly how {@code quota_released_at} shipped. This test closes the gap the
 * cheap way — it does not replay the migrations, it just insists that every column an
 * entity declares is named somewhere in {@code db/migration}. A textual check is enough
 * to catch the whole "entity changed, migration forgotten" class of mistake, and it
 * costs nothing to run.
 */
class MigrationsCoverEntitiesTest {

    private static final String BASE_PACKAGE = "com.gridstore.huevista";
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    @Test
    void everyEntityColumnAppearsInAMigration() throws IOException {
        String sql = readMigrations();
        List<String> missing = new ArrayList<>();

        for (Class<?> entity : entityClasses()) {
            String table = tableName(entity);
            for (Field field : entity.getDeclaredFields()) {
                String column = columnName(field);
                if (column == null) continue;
                if (!mentions(sql, column)) {
                    missing.add(table + "." + column
                                + "  (" + entity.getSimpleName() + "#" + field.getName() + ")");
                }
            }
        }

        assertThat(missing)
                .withFailMessage("""
                        These entity columns have no Flyway migration, so the deployed app \
                        will fail schema validation on boot. Add them to a new \
                        src/main/resources/db/migration/V<next>__*.sql:
                          %s""", String.join("\n  ", missing))
                .isEmpty();
    }

    /**
     * The same gap, one level down: a value added to an enum must reach the CHECK
     * constraint that guards its column.
     *
     * Hibernate derives that constraint from the enum when it first creates the table and
     * never alters it afterwards, so on the deployed (Flyway-managed) database the allowed
     * values are frozen at whatever the enum held on the day the table was made. Tests
     * can't see it — they build the schema from the current enums every run, so the
     * constraint is always correct here and always stale there. It has shipped twice:
     * ACCESS_CODE broke every access-code redemption (fixed in V21), and Plan.FREE broke
     * every shop signup, because the free tier became the trial while the constraint still
     * named only the four paid tiers.
     *
     * Textual, like the column check above: whatever the newest migration to define the
     * constraint allows must cover every constant of the enum. A column whose constraint
     * no migration mentions is skipped — an absent constraint is permissive, and so
     * rejects nothing.
     */
    @Test
    void everyEnumValueAppearsInItsCheckConstraint() throws IOException {
        String sql = readMigrationsInVersionOrder();
        List<String> stale = new ArrayList<>();

        for (Class<?> entity : entityClasses()) {
            String table = tableName(entity);
            for (Field field : entity.getDeclaredFields()) {
                Class<?> enumType = stringEnumType(field);
                if (enumType == null) continue;
                String constraint = table + "_" + columnName(field) + "_check";
                List<String> allowed = allowedValues(sql, constraint);
                if (allowed == null) continue;   // no constraint in any migration

                for (Object constant : enumType.getEnumConstants()) {
                    String value = ((Enum<?>) constant).name();
                    if (!allowed.contains(value)) {
                        stale.add(constraint + " rejects " + enumType.getSimpleName() + "." + value
                                  + "  (allows: " + String.join(", ", allowed) + ")");
                    }
                }
            }
        }

        assertThat(stale)
                .withFailMessage("""
                        These CHECK constraints are older than their enum, so the deployed \
                        database will reject rows the code writes. Widen them in a new \
                        src/main/resources/db/migration/V<next>__*.sql:
                          %s""", String.join("\n  ", stale))
                .isEmpty();
    }

    /** Migrations must be uniquely numbered — two V22s make Flyway refuse to start. */
    @Test
    void migrationVersionsAreUnique() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<String> versions = files
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith("V") && n.endsWith(".sql"))
                    .map(n -> n.substring(1, n.indexOf("__")))
                    .toList();
            assertThat(versions).doesNotHaveDuplicates();
        }
    }

    private String readMigrations() throws IOException {
        StringBuilder sql = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                sql.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sql.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The migrations concatenated in Flyway's own order, so that when a constraint is
     * dropped and re-added the later definition is the one that reads as current.
     */
    private String readMigrationsInVersionOrder() throws IOException {
        StringBuilder sql = new StringBuilder();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            List<Path> ordered = files
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(MigrationsCoverEntitiesTest::version))
                    .toList();
            for (Path file : ordered) {
                sql.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
            }
        }
        return sql.toString();
    }

    private static int version(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    /**
     * What the newest definition of {@code constraint} allows, or null when no migration
     * defines it. Reads the quoted literals inside that constraint's own CHECK(...) —
     * bounded by the closing parenthesis rather than the statement, or a constraint
     * declared inline in a CREATE TABLE would swallow the values of the one after it and
     * report as permitting them. Covers both spellings in use: the baseline's single-line
     * {@code ANY (ARRAY[...])} and the multi-line one V21 introduced.
     */
    private List<String> allowedValues(String sql, String constraint) {
        var definition = Pattern
                .compile("constraint\\s+" + Pattern.quote(constraint) + "\\s+check\\b",
                         Pattern.CASE_INSENSITIVE)
                .matcher(sql);
        String body = null;
        while (definition.find()) {
            body = checkBody(sql, definition.end());
        }
        if (body == null) return null;

        List<String> values = new ArrayList<>();
        var literal = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(body);
        while (literal.find()) {
            values.add(literal.group(1));
        }
        return values;
    }

    /** The CHECK's parenthesised body: from the first '(' at or after {@code from} to the
     *  one that closes it. Enum literals hold no parentheses, so counting is enough. */
    private String checkBody(String sql, int from) {
        int open = sql.indexOf('(', from);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return sql.substring(open, i + 1);
        }
        return null;
    }

    /** The enum a field persists by name, or null when it isn't one. */
    private Class<?> stringEnumType(Field field) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        if (enumerated == null || enumerated.value() != EnumType.STRING) return null;
        return field.getType().isEnum() ? field.getType() : null;
    }

    /** Whole-word match, so {@code used_at} never satisfies {@code quota_released_at}. */
    private boolean mentions(String sql, String column) {
        return Pattern.compile("\\b" + Pattern.quote(column) + "\\b").matcher(sql).find();
    }

    private List<Class<?>> entityClasses() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(classes).as("entity scan found nothing — the scan is broken, not the schema")
                .isNotEmpty();
        return classes;
    }

    private String tableName(Class<?> entity) {
        Table table = entity.getAnnotation(Table.class);
        return table != null && !table.name().isBlank()
                ? table.name() : camelToSnake(entity.getSimpleName());
    }

    /** The column a field maps to, or null when it isn't a column on this table. */
    private String columnName(Field field) {
        if (Modifier.isStatic(field.getModifiers())
                || Modifier.isTransient(field.getModifiers())
                || field.isSynthetic()
                || field.isAnnotationPresent(Transient.class)
                || field.isAnnotationPresent(OneToMany.class)
                || field.isAnnotationPresent(ManyToMany.class)) {
            return null;
        }
        // The inverse side of a one-to-one owns no column.
        OneToOne oneToOne = field.getAnnotation(OneToOne.class);
        if (oneToOne != null && !oneToOne.mappedBy().isBlank()) {
            return null;
        }

        JoinColumn join = field.getAnnotation(JoinColumn.class);
        if (join != null && !join.name().isBlank()) {
            return join.name();
        }
        Column column = field.getAnnotation(Column.class);
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        // An association with no explicit @JoinColumn defaults to <field>_<referenced pk>.
        boolean association = field.isAnnotationPresent(ManyToOne.class) || oneToOne != null;
        return camelToSnake(field.getName()) + (association ? "_id" : "");
    }

    /**
     * Spring Boot's {@code CamelCaseToUnderscoresNamingStrategy}, reproduced exactly: an
     * underscore goes in only where a lower-case letter is followed by an upper-case one
     * that is itself followed by a lower-case one. The trailing-capital case is the one
     * that bites — {@code rgbR} maps to {@code rgbr}, not {@code rgb_r}.
     */
    private String camelToSnake(String name) {
        StringBuilder out = new StringBuilder(name.replace('.', '_'));
        for (int i = 1; i < out.length() - 1; i++) {
            if (Character.isLowerCase(out.charAt(i - 1))
                    && Character.isUpperCase(out.charAt(i))
                    && Character.isLowerCase(out.charAt(i + 1))) {
                out.insert(i++, '_');
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
