package com.taggu.persistence.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read models for extracted knowledge.
 *
 * <p>Every one of them carries {@code sourceMessageIds}. That is not decoration: a caller who cannot
 * see the evidence cannot check the claim, so the API never returns knowledge without it.
 */
public final class KnowledgeItemViews {

    private KnowledgeItemViews() {}

    public record FactView(
            UUID id,
            String statement,
            String subject,
            double confidence,
            String extractor,
            Instant extractedAt,
            List<UUID> sourceMessageIds) {}

    public record EventView(
            UUID id,
            String title,
            Instant startsAt,
            Instant endsAt,
            String precision,
            String timeExpression,
            String location,
            double confidence,
            String extractor,
            Instant extractedAt,
            List<UUID> sourceMessageIds) {}

    public record ActionItemView(
            UUID id,
            String description,
            String assignee,
            Instant dueAt,
            String duePrecision,
            String dueExpression,
            String status,
            double confidence,
            String extractor,
            Instant extractedAt,
            List<UUID> sourceMessageIds) {}

    public record DecisionView(
            UUID id,
            String statement,
            Instant decidedAt,
            double confidence,
            String extractor,
            Instant extractedAt,
            List<UUID> sourceMessageIds) {}

    public record EntityView(UUID id, String type, String name, long mentionCount) {}

    /** Everything extraction found in one conversation. */
    public record ConversationKnowledge(
            UUID conversationId,
            List<EntityView> entities,
            List<FactView> facts,
            List<EventView> events,
            List<ActionItemView> actions,
            List<DecisionView> decisions) {}
}
