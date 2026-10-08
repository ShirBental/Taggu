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

    @Test
    @DisplayName("a conversation whose messages another conversation's knowledge cites cannot be deleted")
    void conversationCitedFromAnotherCannotBeDeleted() {
        UUID other = knowledgeInAnotherConversationCiting(cited);

        // Nothing in the pipeline produces this today, but the schema allows it, so the schema has to
        // answer for it: the delete is refused whole, rather than quietly stripping the other
        // conversation's fact of its evidence.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId))
                .hasStackTraceContaining("fk_fact_sources_message");

        assertThat(count("conversations")).isEqualTo(2);
        assertThat(count("messages")).isEqualTo(4);
        assertThat(orphans()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM facts WHERE conversation_id = ?", Long.class, other))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("knowledge resting on two conversations survives neither losing its evidence nor blocking forever")
    void sharedKnowledgeGoesWithItsOwnConversationFirst() {
        UUID other = knowledgeInAnotherConversationCiting(cited);

        // Deleting the conversation that owns the shared knowledge is always possible...
        jdbc.update("DELETE FROM conversations WHERE id = ?", other);
        assertThat(count("conversations")).isEqualTo(1);
        assertThat(count("messages")).as("the cited conversation keeps its messages").isEqualTo(3);
        assertThat(orphans()).isZero();

        // ...and once nothing outside cites it, the cited conversation can go as well.
        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);
        assertThat(count("messages")).isZero();
        assertThat(count("facts") + count("events") + count("action_items") + count("decisions"))
                .isZero();
    }

    @Test
    @DisplayName("both conversations can be deleted together in one transaction")
    void citedAndCitingConversationsCanBeDeletedTogether() {
        UUID other = knowledgeInAnotherConversationCiting(cited);

        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);
            jdbc.update("DELETE FROM conversations WHERE id = ?", other);
        });

        assertThat(count("conversations") + count("messages") + count("facts") + count("fact_sources"))
                .isZero();
    }

    @Test
    @DisplayName("deleting one conversation leaves shared tags, entities and participants to the others")
    void sharedClassificationSurvivesDeletingOneConversation() {
        StoreFixtures second = new StoreFixtures("car").say("Dana", "Nir Levy Ltd again");
        UUID other = conversationStore.save(second.parsed()).conversationId();
        for (StoreFixtures.Cited use : List.of(new StoreFixtures.Cited(conversationId, cited), new StoreFixtures.Cited(other, second.message(0).id()))) {
            Provenance where = Provenance.ofMessages(use.messageId());
            knowledgeStore.save(
                    use.conversationId(),
                    new ExtractionResult(
                            "test-extractor",
                            List.of(com.taggu.core.knowledge.Tag.of("house", 0.5, where)),
                            List.of(com.taggu.core.knowledge.ExtractedEntity.of(
                                    com.taggu.core.knowledge.EntityType.ORGANIZATION, "Nir Levy Ltd", 0.5, where)),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of()));
        }
        assertThat(count("entities")).isEqualTo(1);
        assertThat(count("entity_mentions")).isEqualTo(2);

        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);

        // The entity and tag are still there, still attached to the conversation that remains.
        assertThat(count("entities")).isEqualTo(1);
        assertThat(count("tags")).isEqualTo(1);
        assertThat(count("entity_mentions")).isEqualTo(1);
        assertThat(count("message_tags")).isEqualTo(1);
        assertThat(knowledgeStore.findByConversation(other).entities()).singleElement().satisfies(entity -> {
            assertThat(entity.name()).isEqualTo("Nir Levy Ltd");
            assertThat(entity.mentionCount()).isEqualTo(1);
        });
        // Dana spoke in both; she is still a participant of the one that is left.
        assertThat(conversationStore.findConversation(other).orElseThrow().participants())
                .extracting(participant -> participant.displayName())
                .containsExactly("Dana");
    }

    @Test
    @DisplayName("deleting a conversation keeps the record of its imports, without the link")
    void importRunsOutliveTheirConversation() {
        UUID run = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO import_runs (id, conversation_id, source, status, started_at) VALUES (?, ?, 'OTHER', 'SUCCEEDED', now())",
                run,
                conversationId);

        jdbc.update("DELETE FROM conversations WHERE id = ?", conversationId);

        assertThat(jdbc.queryForObject("SELECT conversation_id FROM import_runs WHERE id = ?", UUID.class, run))
                .isNull();
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

    /** A second conversation holding a fact whose evidence is its own message and one from the first. */
    private UUID knowledgeInAnotherConversationCiting(UUID messageInFirstConversation) {
        StoreFixtures second = new StoreFixtures("car").say("Ron", "Same price as the roof");
        UUID other = conversationStore.save(second.parsed()).conversationId();
        knowledgeStore.save(
                other,
                new ExtractionResult(
                        "test-extractor",
                        List.of(),
                        List.of(),
                        List.of(new Fact(
                                "The car costs what the roof did",
                                null,
                                0.5,
                                Provenance.ofMessages(second.message(0).id(), messageInFirstConversation))),
                        List.of(),
                        List.of(),
                        List.of()));
        return other;
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
