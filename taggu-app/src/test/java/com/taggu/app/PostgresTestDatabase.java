package com.taggu.app;

import java.net.URI;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The real PostgreSQL the {@code *PostgresTest} classes run against.
 *
 * <p>H2 keeps the everyday suite fast and Docker-free, but it cannot run plpgsql triggers, deferred
 * constraints or full-text search, and those are exactly the parts of the schema that protect
 * provenance. Tests that need them come here.
 *
 * <p>A throwaway container is started with Testcontainers when Docker is available. Without Docker,
 * set {@code TAGGU_TEST_DB_URL} (and optionally {@code TAGGU_TEST_DB_USER} /
 * {@code TAGGU_TEST_DB_PASSWORD}) to a dedicated database. Every test wipes that database, so one whose
 * name does not contain "test" is refused. With neither, the tests are skipped rather than failed.
 */
final class PostgresTestDatabase {

    static final String URL_ENV = "TAGGU_TEST_DB_URL";
    private static final String USER_ENV = "TAGGU_TEST_DB_USER";
    private static final String PASSWORD_ENV = "TAGGU_TEST_DB_PASSWORD";

    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {}

    /** Why these tests cannot run here, or {@code null} when they can. */
    static String unavailableReason() {
        String url = System.getenv(URL_ENV);
        if (url != null && !url.isBlank()) {
            return databaseName(url).contains("test")
                    ? null
                    : URL_ENV + " must name a database containing \"test\": these tests wipe it";
        }
        return dockerAvailable() ? null : "no Docker for Testcontainers and " + URL_ENV + " is not set";
    }

    /** Points a Spring test context at the database, starting the container on first use. */
    static synchronized void register(DynamicPropertyRegistry registry) {
        String url = System.getenv(URL_ENV);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> envOr(USER_ENV, System.getProperty("user.name")));
            registry.add("spring.datasource.password", () -> envOr(PASSWORD_ENV, ""));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    /** Empties the database and rebuilds it with every migration, exactly as a deployment would. */
    static void rebuild(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static String databaseName(String jdbcUrl) {
        String path = URI.create(jdbcUrl.replaceFirst("^jdbc:", "")).getPath();
        return path == null ? "" : path.replaceFirst("^/", "");
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
