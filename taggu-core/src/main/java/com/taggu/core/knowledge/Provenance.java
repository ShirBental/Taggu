package com.taggu.core.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The messages that support a piece of extracted knowledge.
 *
 * <p>Provenance is a hard requirement of this system: the type cannot be constructed empty, so a
 * {@link Fact}, {@link Event}, {@link ActionItem} or {@link Decision} that does not know its
 * evidence cannot be built, let alone persisted.
 */
public record Provenance(List<MessageEvidence> evidence) {

    public Provenance {
        if (evidence == null || evidence.isEmpty()) {
            throw new IllegalArgumentException("extracted knowledge must cite at least one source message");
        }
        evidence = List.copyOf(evidence);
    }

    public static Provenance ofMessages(UUID... messageIds) {
        List<MessageEvidence> items = new ArrayList<>();
        for (UUID id : messageIds) {
            items.add(MessageEvidence.of(id));
        }
        return new Provenance(items);
    }

    public static Provenance ofMessages(Collection<UUID> messageIds) {
        return new Provenance(messageIds.stream().map(MessageEvidence::of).toList());
    }

    /** The distinct messages cited, in citation order. */
    public Set<UUID> messageIds() {
        Set<UUID> ids = new LinkedHashSet<>();
        for (MessageEvidence item : evidence) {
            ids.add(item.messageId());
        }
        return ids;
    }
}
