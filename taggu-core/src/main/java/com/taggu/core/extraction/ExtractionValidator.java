package com.taggu.core.extraction;

import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Provenance;
import com.taggu.core.model.CanonicalMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Checks what an extractor returned against the window it was given, before anything is stored.
 *
 * <p>The knowledge types already refuse to exist without provenance, a confidence in range and some
 * text. What they cannot know is whether the evidence is real. That is the contract of
 * {@link KnowledgeExtractor#extract}, and this is where it is enforced rather than assumed:
 *
 * <ul>
 *   <li>every cited message must be one the extractor was actually shown, lead-in or focus. An id from
 *       anywhere else is evidence the extractor cannot have read, whatever it claims;
 *   <li>at least one cited message must be in the window's focus. Knowledge resting on lead-in alone
 *       belongs to the earlier window that owned those messages, and would be stored twice.
 * </ul>
 *
 * <p>An item that fails is dropped and counted; the rest of the result is kept. A rule-based
 * extractor should never trip this. A model-backed one can cite a message that does not exist, and
 * this is the point at which that is caught.
 */
public final class ExtractionValidator {

    /**
     * @param result the items that passed
     * @param rejected how many items were dropped
     */
    public record Validated(ExtractionResult result, int rejected) {}

    public Validated validate(ExtractionContext context, ExtractionResult result) {
        Set<UUID> shown = new HashSet<>();
        Set<UUID> focus = new HashSet<>();
        for (CanonicalMessage message : context.leadIn()) {
            shown.add(message.id());
        }
        for (CanonicalMessage message : context.focus()) {
            shown.add(message.id());
            focus.add(message.id());
        }

        Filter filter = new Filter(shown, focus);
        ExtractionResult kept = new ExtractionResult(
                result.extractorName(),
                filter.keep(result.tags(), tag -> tag.provenance()),
                filter.keep(result.entities(), entity -> entity.provenance()),
                filter.keep(result.facts(), fact -> fact.provenance()),
                filter.keep(result.events(), event -> event.provenance()),
                filter.keep(result.actions(), action -> action.provenance()),
                filter.keep(result.decisions(), decision -> decision.provenance()));
        return new Validated(kept, filter.rejected);
    }

    private static final class Filter {
        private final Set<UUID> shown;
        private final Set<UUID> focus;
        private int rejected;

        private Filter(Set<UUID> shown, Set<UUID> focus) {
            this.shown = shown;
            this.focus = focus;
        }

        private <T> List<T> keep(List<T> items, Function<T, Provenance> provenanceOf) {
            List<T> kept = new ArrayList<>(items.size());
            for (T item : items) {
                if (item != null && grounded(provenanceOf.apply(item))) {
                    kept.add(item);
                } else {
                    rejected++;
                }
            }
            return kept;
        }

        private boolean grounded(Provenance provenance) {
            Set<UUID> cited = provenance.messageIds();
            return shown.containsAll(cited) && cited.stream().anyMatch(focus::contains);
        }
    }
}
