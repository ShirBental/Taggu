package com.taggu.persistence;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Gives each test a clean PostgreSQL built by every migration, not just the portable V1.
 *
 * <p>This is where the database's own guarantees are asserted: the provenance triggers, the deferred
 * constraints and anything else H2 cannot run.
 */
@RequiresPostgres
abstract class PostgresPersistenceTestBase extends PersistenceTestBase {

    @Autowired
    private DataSource postgres;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @BeforeEach
    @Override
    void resetSchema() {
        PostgresTestDatabase.rebuild(postgres);
    }
}
