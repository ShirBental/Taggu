package com.taggu.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database's own refusal to hold knowledge without evidence, asserted against the triggers
 * themselves rather than trusted from a note in the README.
 */
class ProvenanceTriggerPostgresTest extends PostgresPersistenceTestBase {

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    private UUID conversationId;
    private UUID messageId;

    @BeforeEach
    void storeOneMessage() {
        StoreFixtures fixture = new StoreFixtures("roof").say("Dana", "He quoted $1,550");
        conversationId = conversationStore.save(fixture.parsed()).conversationId();
        messageId = fixture.message(0).id();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a knowledge row with no source row cannot be committed")
    @CsvSource(delimiter = '|', textBlock = """
            facts        | INSERT INTO facts (id, conversation_id, statement, confidence, extractor, extracted_at) VALUES (?, ?, 's', 0.5, 'test', now())
            events       | INSERT INTO events (id, conversation_id, title, time_precision, confidence, extractor, extracted_at) VALUES (?, ?, 't', 'UNRESOLVED', 0.5, 'test', now())
            action_items | INSERT INTO action_items (id, conversation_id, description, due_precision, status, confidence, extractor, extracted_at) VALUES (?, ?, 'd', 'UNRESOLVED', 'OPEN', 0.5, 'test', now())
            decisions    | INSERT INTO decisions (id, conversation_id, statement, confidence, extractor, extracted_at) VALUES (?, ?, 's', 0.5, 'test', now())
            """)
    void rejectsKnowledgeWithoutSources(String table, String insert) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                        status -> jdbc.update(insert, UUID.randomUUID(), conversationId)))
                .hasStackTraceContaining("without provenance");

        assertThat(count(table)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a knowledge row and its source row commit together")
    @CsvSource(delimiter = '|', textBlock = """
            facts        | fact_sources        | fact_id        | INSERT INTO facts (id, conversation_id, statement, confidence, extractor, extracted_at) VALUES (?, ?, 's', 0.5, 'test', now())
            events       | event_sources       | event_id       | INSERT INTO events (id, conversation_id, title, time_precision, confidence, extractor, extracted_at) VALUES (?, ?, 't', 'UNRESOLVED', 0.5, 'test', now())
            action_items | action_item_sources | action_item_id | INSERT INTO action_items (id, conversation_id, description, due_precision, status, confidence, extractor, extracted_at) VALUES (?, ?, 'd', 'UNRESOLVED', 'OPEN', 0.5, 'test', now())
            decisions    | decision_sources    | decision_id    | INSERT INTO decisions (id, conversation_id, statement, confidence, extractor, extracted_at) VALUES (?, ?, 's', 0.5, 'test', now())
            """)
    void acceptsKnowledgeWithSources(String table, String sourceTable, String ownerColumn, String insert) {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jdbc.update(insert, id, conversationId);
            jdbc.update(
                    "INSERT INTO %s (%s, message_id) VALUES (?, ?)".formatted(sourceTable, ownerColumn), id, messageId);
        });

        assertThat(count(table)).isEqualTo(1);
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
