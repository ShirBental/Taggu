package com.taggu.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taggu.core.knowledge.ActionItem;
import com.taggu.core.knowledge.ActionItemStatus;
import com.taggu.core.knowledge.Decision;
import com.taggu.core.knowledge.Event;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Fact;
import com.taggu.core.knowledge.Provenance;
import com.taggu.core.knowledge.TimePrecision;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stored knowledge must not be able to lose its evidence afterwards.
 *
 * <p>Regression tests for V3: before it, deleting a cited message cascaded into the source tables and
 * left knowledge rows with nothing behind them.
 */
class ProvenanceProtectionPostgresTest extends PostgresPersistenceTestBase {

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    private UUID conversationId;
    private UUID cited;
    private UUID alsoCited;
    private UUID uncited;

    /** One item of each kind citing the first message; the event cites the second as well. */
    @BeforeEach
    void storeKnowledge() {
        StoreFixtures fixture = new StoreFixtures("roof")
                .say("Dana", "He quoted $1,550")
                .say("Ron", "Deal, Sunday around 4")
                .say("Dana", "ok");
        conversationId = conversationStore.save(fixture.parsed()).conversationId();
        cited = fixture.message(0).id();
        alsoCited = fixture.message(1).id();
        uncited = fixture.message(2).id();
        Provenance first = Provenance.ofMessages(cited);
        knowledgeStore.save(
                conversationId,
                new ExtractionResult(
                        "test-extractor",
                        List.of(),
                        List.of(),
                        List.of(new Fact("He quoted $1,550", "$1,550", 0.5, first)),
                        List.of(new Event(
                                "Meeting",
                                null,
                                null,
                                TimePrecision.UNRESOLVED,
                                "Sunday around 4",
                                null,
                                0.5,
                                Provenance.ofMessages(cited, alsoCited))),
                        List.of(new ActionItem(
                                "pay him", "Ron", null, TimePrecision.UNRESOLVED, null, ActionItemStatus.OPEN, 0.5, first)),
                        List.of(new Decision("Deal", null, 0.5, first))));
    }

    @Test
    @DisplayName("a message cited as evidence cannot be deleted from under the knowledge it supports")
    void evidenceMessageCannotBeDeleted() {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM messages WHERE id = ?", cited))
                .hasStackTraceContaining("_message");

        assertThat(count("messages")).isEqualTo(3);
        assertThat(orphans()).isZero();
    }

    @Test
    @DisplayName("a message nothing cites can still be deleted")
    void uncitedMessageCanBeDeleted() {
        jdbc.update("DELETE FROM messages WHERE id = ?", uncited);

        assertThat(count("messages")).isEqualTo(2);
    }

    @Test
    @DisplayName("deleting a conversation removes its messages and its knowledge together")
    void conversationCanStillBeDeleted() {
        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);

        assertThat(count("messages")).isZero();
        assertThat(count("facts") + count("events") + count("action_items") + count("decisions"))
                .isZero();
        assertThat(count("fact_sources") + count("event_sources")).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("the last source row of a knowledge row cannot be deleted")
    @CsvSource({"fact_sources", "action_item_sources", "decision_sources"})
    void lastSourceCannotBeDeleted(String sourceTable) {
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + sourceTable))
                .hasStackTraceContaining("without provenance");

        assertThat(count(sourceTable)).isEqualTo(1);
    }

    @Test
    @DisplayName("one of several source rows can go, the last one cannot")
    void oneOfSeveralSourcesCanBeDeleted() {
        jdbc.update("DELETE FROM event_sources WHERE message_id = ?", alsoCited);
        assertThat(count("event_sources")).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM event_sources"))
                .hasStackTraceContaining("without provenance");
        assertThat(count("event_sources")).isEqualTo(1);
    }

    @Test
    @DisplayName("a source row cannot be re-pointed away from the knowledge row it backs")
    void sourceCannotBeRepointed() {
        UUID otherFact = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                    jdbc.update(
                            "INSERT INTO facts (id, conversation_id, statement, confidence, extractor, extracted_at) "
                                    + "VALUES (?, ?, 'other', 0.5, 'test', now())",
                            otherFact,
                            conversationId);
                    jdbc.update("UPDATE fact_sources SET fact_id = ?", otherFact);
                }))
                .hasStackTraceContaining("without provenance");

        assertThat(orphans()).isZero();
    }

    @Test
    @DisplayName("deleting a knowledge row takes its source rows with it")
    void knowledgeRowCanBeDeleted() {
        jdbc.update("DELETE FROM facts");

        assertThat(count("fact_sources")).isZero();
    }

    @Test
    @DisplayName("a knowledge row inserted and deleted in one transaction does not trip the insert check")
    void insertThenDeleteInOneTransaction() {
        UUID id = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            jdbc.update(
                    "INSERT INTO facts (id, conversation_id, statement, confidence, extractor, extracted_at) "
                            + "VALUES (?, ?, 'temporary', 0.5, 'test', now())",
                    id,
                    conversationId);
            jdbc.update("DELETE FROM facts WHERE id = ?", id);
        });

        assertThat(count("facts")).isEqualTo(1);
    }

    private long orphans() {
        long total = 0;
        String[][] kinds = {
            {"facts", "fact_sources", "fact_id"},
            {"events", "event_sources", "event_id"},
            {"action_items", "action_item_sources", "action_item_id"},
            {"decisions", "decision_sources", "decision_id"}
        };
        for (String[] kind : kinds) {
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM %s o WHERE NOT EXISTS (SELECT 1 FROM %s s WHERE s.%s = o.id)"
                            .formatted(kind[0], kind[1], kind[2]),
                    Long.class);
            total += count == null ? 0 : count;
        }
        return total;
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}
