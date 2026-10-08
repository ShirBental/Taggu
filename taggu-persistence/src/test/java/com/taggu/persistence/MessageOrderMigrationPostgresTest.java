package com.taggu.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.taggu.persistence.view.MessageView;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** V5 against a database that already holds messages, which is what every existing installation is. */
class MessageOrderMigrationPostgresTest extends PostgresPersistenceTestBase {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationStore store;

    @Test
    @DisplayName("messages stored before V5 are put in the order of the line numbers recorded with them")
    void backfillsOrderFromRecordedLineNumbers() {
        migrateFromScratchTo("4");
        UUID conversationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO conversations (id, source, source_conversation_id, title, imported_at) "
                        + "VALUES (?, 'OTHER', 'old', 'Old', now())",
                conversationId);
        // Same timestamp; the source ids sort in the opposite order to the lines they came from.
        insertOldMessage(conversationId, "a", "third", "3");
        insertOldMessage(conversationId, "b", "second", "2");
        insertOldMessage(conversationId, "c", "first", "1");
        insertOldMessage(conversationId, "d", "no line number recorded", null);

        migrateTo("latest");

        assertThat(store.findMessages(conversationId, 10, 0)).extracting(MessageView::text)
                .containsExactly("no line number recorded", "first", "second", "third");
    }

    private void insertOldMessage(UUID conversationId, String sourceMessageId, String body, String lineNumber) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO messages (id, conversation_id, source, source_message_id, sent_at, message_type, body) "
                        + "VALUES (?, ?, 'OTHER', ?, TIMESTAMPTZ '2024-03-14 18:02:00+00', 'TEXT', ?)",
                id,
                conversationId,
                sourceMessageId,
                body);
        if (lineNumber != null) {
            jdbc.update(
                    "INSERT INTO message_metadata (message_id, metadata_key, metadata_value) VALUES (?, 'lineNumber', ?)",
                    id,
                    lineNumber);
        }
    }

    private void migrateFromScratchTo(String version) {
        flyway(version).clean();
        migrateTo(version);
    }

    private void migrateTo(String version) {
        flyway(version).migrate();
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .target(target)
                .load();
    }
}
