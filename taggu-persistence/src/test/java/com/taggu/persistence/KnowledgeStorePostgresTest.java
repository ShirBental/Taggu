package com.taggu.persistence;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs every {@link KnowledgeStoreTest} case again on PostgreSQL with all migrations applied, so the store
 * is proven on the database it ships on and not only on its H2 stand-in.
 */
@RequiresPostgres
class KnowledgeStorePostgresTest extends KnowledgeStoreTest {

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
