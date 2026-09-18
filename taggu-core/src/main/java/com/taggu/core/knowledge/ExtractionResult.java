package com.taggu.core.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Everything one extractor found in one conversation window.
 *
 * <p>The shape separates classification ({@code tags}, {@code entities}) from knowledge
 * ({@code facts}, {@code events}, {@code actions}, {@code decisions}), because the two are consumed
 * differently: classification narrows a search, knowledge answers a question.
 *
 * @param extractorName which extractor produced this, kept so results can be re-attributed when a
 *     better extractor replaces an earlier one
 */
public record ExtractionResult(
        String extractorName,
        List<Tag> tags,
        List<ExtractedEntity> entities,
        List<Fact> facts,
        List<Event> events,
        List<ActionItem> actions,
        List<Decision> decisions) {

    public ExtractionResult {
        extractorName = extractorName == null || extractorName.isBlank() ? "unknown" : extractorName;
        tags = copy(tags);
        entities = copy(entities);
        facts = copy(facts);
        events = copy(events);
        actions = copy(actions);
        decisions = copy(decisions);
    }

    public static ExtractionResult empty(String extractorName) {
        return new ExtractionResult(extractorName, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return tags.isEmpty()
                && entities.isEmpty()
                && facts.isEmpty()
                && events.isEmpty()
                && actions.isEmpty()
                && decisions.isEmpty();
    }

    public int size() {
        return tags.size() + entities.size() + facts.size() + events.size() + actions.size() + decisions.size();
    }

    /** Concatenates per-window results into one result for a whole conversation. */
    public static ExtractionResult merge(String extractorName, Collection<ExtractionResult> results) {
        List<Tag> tags = new ArrayList<>();
        List<ExtractedEntity> entities = new ArrayList<>();
        List<Fact> facts = new ArrayList<>();
        List<Event> events = new ArrayList<>();
        List<ActionItem> actions = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        for (ExtractionResult result : results) {
            tags.addAll(result.tags());
            entities.addAll(result.entities());
            facts.addAll(result.facts());
            events.addAll(result.events());
            actions.addAll(result.actions());
            decisions.addAll(result.decisions());
        }
        return new ExtractionResult(extractorName, tags, entities, facts, events, actions, decisions);
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public static Builder builder(String extractorName) {
        return new Builder(extractorName);
    }

    /** Collects items while an extractor walks a window. */
    public static final class Builder {
        private final String extractorName;
        private final List<Tag> tags = new ArrayList<>();
        private final List<ExtractedEntity> entities = new ArrayList<>();
        private final List<Fact> facts = new ArrayList<>();
        private final List<Event> events = new ArrayList<>();
        private final List<ActionItem> actions = new ArrayList<>();
        private final List<Decision> decisions = new ArrayList<>();

        private Builder(String extractorName) {
            this.extractorName = extractorName;
        }

        public Builder tag(Tag tag) {
            tags.add(tag);
            return this;
        }

        public Builder entity(ExtractedEntity entity) {
            entities.add(entity);
            return this;
        }

        public Builder fact(Fact fact) {
            facts.add(fact);
            return this;
        }

        public Builder event(Event event) {
            events.add(event);
            return this;
        }

        public Builder action(ActionItem action) {
            actions.add(action);
            return this;
        }

        public Builder decision(Decision decision) {
            decisions.add(decision);
            return this;
        }

        public ExtractionResult build() {
            return new ExtractionResult(extractorName, tags, entities, facts, events, actions, decisions);
        }
    }
}
