package com.taggu.app;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs every {@link ImportApiTest} case again on PostgreSQL with all migrations applied: the whole
 * pipeline, through HTTP, on the database it ships on.
 */
@RequiresPostgres
class ImportApiPostgresTest extends ImportApiTest {

    @Autowired
    private DataSource postgres;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        // The H2 configuration builds the schema from V1 alone at startup; here Flyway owns it.
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @BeforeEach
    @Override
    void resetSchema() {
        PostgresTestDatabase.rebuild(postgres);
    }
}
