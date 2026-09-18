package com.taggu.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives each test a clean database built by the project's real V1 migration.
 *
 * <p>H2 in PostgreSQL mode stands in for PostgreSQL so the suite runs anywhere with no Docker. That
 * buys portability at a price worth naming: V2's plpgsql provenance triggers cannot run here, so they
 * are verified against a real PostgreSQL rather than by this suite. Everything V1 declares - tables,
 * keys, uniqueness, cascades - is exercised exactly as it ships.
 *
 * <p>The test method itself runs outside a transaction, so the code under test manages its own.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ConversationStore.class, KnowledgeStore.class, ImportRunStore.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
abstract class PersistenceTestBase {

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("sql/reset.sql")));
            ScriptUtils.executeSqlScript(
                    connection, new EncodedResource(new ClassPathResource("db/migration/V1__initial_schema.sql")));
        }
    }
}
