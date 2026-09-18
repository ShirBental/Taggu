package com.taggu.core.knowledge;

import java.util.Objects;
import java.util.UUID;

/**
 * One message that supports a piece of extracted knowledge.
 *
 * @param messageId the canonical message acting as evidence
 * @param excerpt the span of the message the extractor relied on, or {@code null} when the whole
 *     message is the evidence. Excerpts are stored locally alongside the messages they come from and
 *     are never written to logs.
 */
public record MessageEvidence(UUID messageId, String excerpt) {

    public MessageEvidence {
        Objects.requireNonNull(messageId, "messageId");
    }

    public static MessageEvidence of(UUID messageId) {
        return new MessageEvidence(messageId, null);
    }
}
