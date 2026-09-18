package com.taggu.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.taggu.core.knowledge.ActionItem;
import com.taggu.core.knowledge.ActionItemStatus;
import com.taggu.core.knowledge.Decision;
import com.taggu.core.knowledge.EntityType;
import com.taggu.core.knowledge.Event;
import com.taggu.core.knowledge.ExtractedEntity;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Fact;
import com.taggu.core.knowledge.Provenance;
import com.taggu.core.knowledge.Tag;
import com.taggu.core.knowledge.TimePrecision;
import com.taggu.persistence.view.KnowledgeItemViews.ConversationKnowledge;
import com.taggu.persistence.view.MessageView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class KnowledgeStoreTest extends PersistenceTestBase {

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("stores every kind of knowledge and reads it back with its evidence")
    void storesKnowledgeWithProvenance() {
        StoreFixtures fixture = new StoreFixtures("roof")
                .say("Dana", "He quoted $1,550 for the repair")
                .say("Ron", "Let's go with them")
                .say("Ron", "I'll call him tomorrow");
        UUID conversationId = conversationStore.save(fixture.parsed()).conversationId();
        UUID first = fixture.message(0).id();
        UUID second = fixture.message(1).id();

        knowledgeStore.save(conversationId, result(fixture));

        ConversationKnowledge knowledge = knowledgeStore.findByConversation(conversationId);
        assertThat(knowledge.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.statement()).contains("$1,550");
            assertThat(fact.sourceMessageIds()).containsExactly(first);
        });
        assertThat(knowledge.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.sourceMessageIds()).containsExactly(second));
        assertThat(knowledge.actions()).singleElement().satisfies(action -> {
            assertThat(action.assignee()).isEqualTo("Ron");
            assertThat(action.sourceMessageIds()).containsExactly(second);
        });
        assertThat(knowledge.events()).singleElement().satisfies(event -> {
            assertThat(event.precision()).isEqualTo("APPROXIMATE");
            assertThat(event.sourceMessageIds()).containsExactlyInAnyOrder(first, second);
        });
        assertThat(knowledge.entities()).extracting(entity -> entity.type() + ":" + entity.name())
                .containsExactly("ORGANIZATION:Nir Levy Ltd");
    }

    @Test
    @DisplayName("nothing reaches the knowledge tables without a row saying where it came from")
    void everyStoredItemHasEvidence() {
        StoreFixtures fixture = new StoreFixtures("roof").say("Dana", "He quoted $1,550").say("Ron", "Deal");
        UUID conversationId = conversationStore.save(fixture.parsed()).conversationId();

        knowledgeStore.save(conversationId, result(fixture));

        assertThat(orphanCount("facts", "fact_sources", "fact_id")).isZero();
        assertThat(orphanCount("events", "event_sources", "event_id")).isZero();
        assertThat(orphanCount("action_items", "action_item_sources", "action_item_id")).isZero();
        assertThat(orphanCount("decisions", "decision_sources", "decision_id")).isZero();
    }

    @Test
    @DisplayName("re-running extraction rewrites the same rows instead of duplicating them")
    void isIdempotent() {
        StoreFixtures fixture = new StoreFixtures("roof").say("Dana", "He quoted $1,550").say("Ron", "Deal");
        UUID conversationId = conversationStore.save(fixture.parsed()).conversationId();

        knowledgeStore.save(conversationId, result(fixture));
        knowledgeStore.save(conversationId, result(fixture));

        ConversationKnowledge knowledge = knowledgeStore.findByConversation(conversationId);
        assertThat(knowledge.facts()).hasSize(1);
        assertThat(knowledge.events()).hasSize(1);
        assertThat(knowledge.actions()).hasSize(1);
        assertThat(knowledge.decisions()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fact_sources", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("tags come back on the messages they were found on")
    void attachesTagsToMessages() {
        StoreFixtures fixture = new StoreFixtures("roof").say("Dana", "He quoted $1,550").say("Ron", "Deal");
        UUID conversationId = conversationStore.save(fixture.parsed()).conversationId();

        knowledgeStore.save(conversationId, result(fixture));

        List<MessageView> messages = conversationStore.findMessages(conversationId, 10, 0);
        assertThat(messages.get(0).tags()).containsExactly("house", "purchase");
        assertThat(messages.get(1).tags()).isEmpty();
    }

    /** A result covering each kind of knowledge, built by hand so the store is tested on its own. */
    private ExtractionResult result(StoreFixtures fixture) {
        UUID first = fixture.message(0).id();
        UUID second = fixture.message(1).id();
        Provenance firstOnly = Provenance.ofMessages(first);
        return new ExtractionResult(
                "test-extractor",
                List.of(Tag.of("house", 0.6, firstOnly), Tag.of("purchase", 0.6, firstOnly)),
                List.of(ExtractedEntity.of(EntityType.ORGANIZATION, "Nir Levy Ltd", 0.5, firstOnly)),
                List.of(new Fact("He quoted $1,550 for the repair", "$1,550", 0.55, firstOnly)),
                List.of(new Event(
                        "Roof repair",
                        Instant.parse("2024-03-17T15:00:00Z"),
                        null,
                        TimePrecision.APPROXIMATE,
                        "Sunday around 4",
                        null,
                        0.5,
                        Provenance.ofMessages(first, second))),
                List.of(new ActionItem(
                        "call him tomorrow",
                        "Ron",
                        null,
                        TimePrecision.UNRESOLVED,
                        null,
                        ActionItemStatus.OPEN,
                        0.5,
                        Provenance.ofMessages(second))),
                List.of(new Decision("Let's go with them", Instant.parse("2024-03-14T17:03:00Z"), 0.5,
                        Provenance.ofMessages(second))));
    }

    private long orphanCount(String table, String sourceTable, String ownerColumn) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM %s o WHERE NOT EXISTS (SELECT 1 FROM %s s WHERE s.%s = o.id)"
                        .formatted(table, sourceTable, ownerColumn),
                Long.class);
        return count == null ? 0 : count;
    }
}
