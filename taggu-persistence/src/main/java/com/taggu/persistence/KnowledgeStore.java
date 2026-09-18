package com.taggu.persistence;

import com.taggu.core.knowledge.ActionItem;
import com.taggu.core.knowledge.Decision;
import com.taggu.core.knowledge.Event;
import com.taggu.core.knowledge.ExtractedEntity;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Fact;
import com.taggu.core.knowledge.MessageEvidence;
import com.taggu.core.knowledge.Provenance;
import com.taggu.core.knowledge.Tag;
import com.taggu.persistence.view.KnowledgeItemViews.ActionItemView;
import com.taggu.persistence.view.KnowledgeItemViews.ConversationKnowledge;
import com.taggu.persistence.view.KnowledgeItemViews.DecisionView;
import com.taggu.persistence.view.KnowledgeItemViews.EntityView;
import com.taggu.persistence.view.KnowledgeItemViews.EventView;
import com.taggu.persistence.view.KnowledgeItemViews.FactView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and reads extracted knowledge.
 *
 * <p>Every write goes through {@link #insertSources}, so a fact, event, action item or decision is
 * never inserted without the rows that say where it came from. PostgreSQL enforces the same rule with
 * a deferred trigger; this class is the reason the trigger should never fire.
 *
 * <p>Ids are derived from the item and its evidence, so re-running extraction over a conversation
 * rewrites the same rows instead of piling up duplicates.
 */
@Repository
public class KnowledgeStore {

    private final NamedParameterJdbcTemplate jdbc;

    public KnowledgeStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int save(UUID conversationId, ExtractionResult result) {
        Instant extractedAt = Instant.now();
        int written = 0;
        for (Tag tag : result.tags()) {
            written += saveTag(tag, result.extractorName());
        }
        for (ExtractedEntity entity : result.entities()) {
            written += saveEntity(entity, result.extractorName());
        }
        for (Fact fact : result.facts()) {
            written += saveFact(conversationId, fact, result.extractorName(), extractedAt);
        }
        for (Event event : result.events()) {
            written += saveEvent(conversationId, event, result.extractorName(), extractedAt);
        }
        for (ActionItem action : result.actions()) {
            written += saveAction(conversationId, action, result.extractorName(), extractedAt);
        }
        for (Decision decision : result.decisions()) {
            written += saveDecision(conversationId, decision, result.extractorName(), extractedAt);
        }
        return written;
    }

    // --- classification ----------------------------------------------------------------------

    private int saveTag(Tag tag, String extractor) {
        UUID tagId = StoredIds.tagId(tag.slug());
        insertIfAbsent(
                "tags",
                "INSERT INTO tags (id, slug, label) VALUES (:id, :slug, :label)",
                "SELECT count(*) FROM tags WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", tagId)
                        .addValue("slug", tag.slug())
                        .addValue("label", tag.label()));

        int written = 0;
        for (UUID messageId : tag.provenance().messageIds()) {
            written += jdbc.update(
                    """
                    INSERT INTO message_tags (message_id, tag_id, confidence, extractor)
                    SELECT :messageId, :tagId, :confidence, :extractor
                     WHERE NOT EXISTS (SELECT 1 FROM message_tags
                                        WHERE message_id = :messageId AND tag_id = :tagId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("messageId", messageId)
                            .addValue("tagId", tagId)
                            .addValue("confidence", tag.confidence())
                            .addValue("extractor", extractor));
        }
        return written;
    }

    private int saveEntity(ExtractedEntity entity, String extractor) {
        UUID entityId = StoredIds.entityId(entity.type().name(), entity.normalizedName());
        insertIfAbsent(
                "entities",
                """
                INSERT INTO entities (id, entity_type, name, normalized_name)
                VALUES (:id, :type, :name, :normalizedName)
                """,
                "SELECT count(*) FROM entities WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", entityId)
                        .addValue("type", entity.type().name())
                        .addValue("name", entity.name())
                        .addValue("normalizedName", entity.normalizedName()));

        int written = 0;
        for (UUID messageId : entity.provenance().messageIds()) {
            written += jdbc.update(
                    """
                    INSERT INTO entity_mentions (entity_id, message_id, confidence, extractor)
                    SELECT :entityId, :messageId, :confidence, :extractor
                     WHERE NOT EXISTS (SELECT 1 FROM entity_mentions
                                        WHERE entity_id = :entityId AND message_id = :messageId)
                    """,
                    new MapSqlParameterSource()
                            .addValue("entityId", entityId)
                            .addValue("messageId", messageId)
                            .addValue("confidence", entity.confidence())
                            .addValue("extractor", extractor));
        }
        return written;
    }

    // --- knowledge ---------------------------------------------------------------------------

    private int saveFact(UUID conversationId, Fact fact, String extractor, Instant extractedAt) {
        List<UUID> sources = List.copyOf(fact.provenance().messageIds());
        UUID id = StoredIds.knowledgeId("fact", conversationId, extractor, fact.statement(), sources);
        boolean inserted = insertIfAbsent(
                "facts",
                """
                INSERT INTO facts (id, conversation_id, statement, subject, confidence, extractor, extracted_at)
                VALUES (:id, :conversationId, :statement, :subject, :confidence, :extractor, :extractedAt)
                """,
                "SELECT count(*) FROM facts WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("conversationId", conversationId)
                        .addValue("statement", fact.statement())
                        .addValue("subject", fact.subject())
                        .addValue("confidence", fact.confidence())
                        .addValue("extractor", extractor)
                        .addValue("extractedAt", Timestamp.from(extractedAt)));
        insertSources("fact_sources", "fact_id", id, fact.provenance());
        return inserted ? 1 : 0;
    }

    private int saveEvent(UUID conversationId, Event event, String extractor, Instant extractedAt) {
        List<UUID> sources = List.copyOf(event.provenance().messageIds());
        UUID id = StoredIds.knowledgeId("event", conversationId, extractor, event.title(), sources);
        boolean inserted = insertIfAbsent(
                "events",
                """
                INSERT INTO events (id, conversation_id, title, starts_at, ends_at, time_precision,
                                    time_expression, location, confidence, extractor, extracted_at)
                VALUES (:id, :conversationId, :title, :startsAt, :endsAt, :precision,
                        :timeExpression, :location, :confidence, :extractor, :extractedAt)
                """,
                "SELECT count(*) FROM events WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("conversationId", conversationId)
                        .addValue("title", event.title())
                        .addValue("startsAt", ConversationStore.timestamp(event.startsAt()))
                        .addValue("endsAt", ConversationStore.timestamp(event.endsAt()))
                        .addValue("precision", event.precision().name())
                        .addValue("timeExpression", event.timeExpression())
                        .addValue("location", event.location())
                        .addValue("confidence", event.confidence())
                        .addValue("extractor", extractor)
                        .addValue("extractedAt", Timestamp.from(extractedAt)));
        insertSources("event_sources", "event_id", id, event.provenance());
        return inserted ? 1 : 0;
    }

    private int saveAction(UUID conversationId, ActionItem action, String extractor, Instant extractedAt) {
        List<UUID> sources = List.copyOf(action.provenance().messageIds());
        UUID id = StoredIds.knowledgeId("action", conversationId, extractor, action.description(), sources);
        boolean inserted = insertIfAbsent(
                "action_items",
                """
                INSERT INTO action_items (id, conversation_id, description, assignee, due_at, due_precision,
                                          due_expression, status, confidence, extractor, extracted_at)
                VALUES (:id, :conversationId, :description, :assignee, :dueAt, :duePrecision,
                        :dueExpression, :status, :confidence, :extractor, :extractedAt)
                """,
                "SELECT count(*) FROM action_items WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("conversationId", conversationId)
                        .addValue("description", action.description())
                        .addValue("assignee", action.assignee())
                        .addValue("dueAt", ConversationStore.timestamp(action.dueAt()))
                        .addValue("duePrecision", action.duePrecision().name())
                        .addValue("dueExpression", action.dueExpression())
                        .addValue("status", action.status().name())
                        .addValue("confidence", action.confidence())
                        .addValue("extractor", extractor)
                        .addValue("extractedAt", Timestamp.from(extractedAt)));
        insertSources("action_item_sources", "action_item_id", id, action.provenance());
        return inserted ? 1 : 0;
    }

    private int saveDecision(UUID conversationId, Decision decision, String extractor, Instant extractedAt) {
        List<UUID> sources = List.copyOf(decision.provenance().messageIds());
        UUID id = StoredIds.knowledgeId("decision", conversationId, extractor, decision.statement(), sources);
        boolean inserted = insertIfAbsent(
                "decisions",
                """
                INSERT INTO decisions (id, conversation_id, statement, decided_at, confidence, extractor, extracted_at)
                VALUES (:id, :conversationId, :statement, :decidedAt, :confidence, :extractor, :extractedAt)
                """,
                "SELECT count(*) FROM decisions WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("conversationId", conversationId)
                        .addValue("statement", decision.statement())
                        .addValue("decidedAt", ConversationStore.timestamp(decision.decidedAt()))
                        .addValue("confidence", decision.confidence())
                        .addValue("extractor", extractor)
                        .addValue("extractedAt", Timestamp.from(extractedAt)));
        insertSources("decision_sources", "decision_id", id, decision.provenance());
        return inserted ? 1 : 0;
    }

    /**
     * Writes the evidence rows for a knowledge item.
     *
     * <p>{@link Provenance} cannot be empty, so reaching here always writes at least one row. The
     * guard is kept anyway because this is the last point at which knowledge without evidence could
     * still get into the database.
     */
    private void insertSources(String table, String ownerColumn, UUID ownerId, Provenance provenance) {
        if (provenance.evidence().isEmpty()) {
            throw new IllegalStateException("refusing to store knowledge without provenance: " + table);
        }
        for (MessageEvidence evidence : provenance.evidence()) {
            jdbc.update(
                    """
                    INSERT INTO %s (%s, message_id, excerpt)
                    SELECT :ownerId, :messageId, :excerpt
                     WHERE NOT EXISTS (SELECT 1 FROM %s WHERE %s = :ownerId AND message_id = :messageId)
                    """
                            .formatted(table, ownerColumn, table, ownerColumn),
                    new MapSqlParameterSource()
                            .addValue("ownerId", ownerId)
                            .addValue("messageId", evidence.messageId())
                            .addValue("excerpt", evidence.excerpt()));
        }
    }

    private boolean insertIfAbsent(String table, String insertSql, String existsSql, MapSqlParameterSource params) {
        Long existing = jdbc.queryForObject(existsSql, params, Long.class);
        if (existing != null && existing > 0) {
            return false;
        }
        jdbc.update(insertSql, params);
        return true;
    }

    // --- reads -------------------------------------------------------------------------------

    public ConversationKnowledge findByConversation(UUID conversationId) {
        MapSqlParameterSource params = new MapSqlParameterSource("conversationId", conversationId);
        Map<UUID, List<UUID>> factSources = sources("fact_sources", "fact_id", "facts", conversationId);
        Map<UUID, List<UUID>> eventSources = sources("event_sources", "event_id", "events", conversationId);
        Map<UUID, List<UUID>> actionSources =
                sources("action_item_sources", "action_item_id", "action_items", conversationId);
        Map<UUID, List<UUID>> decisionSources = sources("decision_sources", "decision_id", "decisions", conversationId);

        List<FactView> facts = jdbc.query(
                """
                SELECT id, statement, subject, confidence, extractor, extracted_at
                  FROM facts WHERE conversation_id = :conversationId ORDER BY extracted_at, statement
                """,
                params,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new FactView(
                            id,
                            rs.getString("statement"),
                            rs.getString("subject"),
                            rs.getDouble("confidence"),
                            rs.getString("extractor"),
                            ConversationStore.instant(rs.getTimestamp("extracted_at")),
                            factSources.getOrDefault(id, List.of()));
                });

        List<EventView> events = jdbc.query(
                """
                SELECT id, title, starts_at, ends_at, time_precision, time_expression, location,
                       confidence, extractor, extracted_at
                  FROM events WHERE conversation_id = :conversationId ORDER BY starts_at NULLS LAST, title
                """,
                params,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new EventView(
                            id,
                            rs.getString("title"),
                            ConversationStore.instant(rs.getTimestamp("starts_at")),
                            ConversationStore.instant(rs.getTimestamp("ends_at")),
                            rs.getString("time_precision"),
                            rs.getString("time_expression"),
                            rs.getString("location"),
                            rs.getDouble("confidence"),
                            rs.getString("extractor"),
                            ConversationStore.instant(rs.getTimestamp("extracted_at")),
                            eventSources.getOrDefault(id, List.of()));
                });

        List<ActionItemView> actions = jdbc.query(
                """
                SELECT id, description, assignee, due_at, due_precision, due_expression, status,
                       confidence, extractor, extracted_at
                  FROM action_items WHERE conversation_id = :conversationId ORDER BY due_at NULLS LAST, description
                """,
                params,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ActionItemView(
                            id,
                            rs.getString("description"),
                            rs.getString("assignee"),
                            ConversationStore.instant(rs.getTimestamp("due_at")),
                            rs.getString("due_precision"),
                            rs.getString("due_expression"),
                            rs.getString("status"),
                            rs.getDouble("confidence"),
                            rs.getString("extractor"),
                            ConversationStore.instant(rs.getTimestamp("extracted_at")),
                            actionSources.getOrDefault(id, List.of()));
                });

        List<DecisionView> decisions = jdbc.query(
                """
                SELECT id, statement, decided_at, confidence, extractor, extracted_at
                  FROM decisions WHERE conversation_id = :conversationId ORDER BY decided_at NULLS LAST, statement
                """,
                params,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new DecisionView(
                            id,
                            rs.getString("statement"),
                            ConversationStore.instant(rs.getTimestamp("decided_at")),
                            rs.getDouble("confidence"),
                            rs.getString("extractor"),
                            ConversationStore.instant(rs.getTimestamp("extracted_at")),
                            decisionSources.getOrDefault(id, List.of()));
                });

        List<EntityView> entities = jdbc.query(
                """
                SELECT e.id, e.entity_type, e.name, count(*) AS mention_count
                  FROM entities e
                  JOIN entity_mentions em ON em.entity_id = e.id
                  JOIN messages m ON m.id = em.message_id
                 WHERE m.conversation_id = :conversationId
                 GROUP BY e.id, e.entity_type, e.name
                 ORDER BY count(*) DESC, e.name
                """,
                params,
                (rs, rowNum) -> new EntityView(
                        rs.getObject("id", UUID.class),
                        rs.getString("entity_type"),
                        rs.getString("name"),
                        rs.getLong("mention_count")));

        return new ConversationKnowledge(conversationId, entities, facts, events, actions, decisions);
    }

    private Map<UUID, List<UUID>> sources(String table, String ownerColumn, String ownerTable, UUID conversationId) {
        Map<UUID, List<UUID>> byOwner = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT s.%s AS owner_id, s.message_id
                  FROM %s s
                  JOIN %s o ON o.id = s.%s
                 WHERE o.conversation_id = :conversationId
                """
                        .formatted(ownerColumn, table, ownerTable, ownerColumn),
                new MapSqlParameterSource("conversationId", conversationId),
                rs -> {
                    byOwner.computeIfAbsent(rs.getObject("owner_id", UUID.class), key -> new ArrayList<>())
                            .add(rs.getObject("message_id", UUID.class));
                });
        return byOwner;
    }
}
