package com.taggu.core.model;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The single message shape every part of the system above ingestion works with.
 *
 * <p>Source adapters translate their own format into this record; nothing downstream of an adapter
 * is permitted to look at {@link #source()} in order to change behaviour.
 *
 * @param id canonical identifier, derived from the source identity of the message
 * @param source the system the message came from
 * @param sourceMessageId how that system identifies the message; exports without real ids get a
 *     content-derived one so that re-imports are idempotent
 * @param conversationId the canonical conversation this belongs to
 * @param senderId the canonical participant who wrote it, or {@code null} for platform messages
 * @param sentAt when it was sent
 * @param messageType what the message carries
 * @param text the message body; may be empty for media-only messages
 * @param replyToMessageId the canonical message this replies to, when the source records one
 * @param metadata small source-specific key/value pairs kept for debugging and future adapters
 * @param rawContent the untouched source line(s), retained so an import can be re-examined
 */
public record CanonicalMessage(
        UUID id,
        SourceType source,
        String sourceMessageId,
        UUID conversationId,
        UUID senderId,
        Instant sentAt,
        MessageType messageType,
        String text,
        UUID replyToMessageId,
        Map<String, String> metadata,
        String rawContent) {

    public CanonicalMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(sentAt, "sentAt");
        Objects.requireNonNull(messageType, "messageType");
        if (sourceMessageId == null || sourceMessageId.isBlank()) {
            throw new IllegalArgumentException("sourceMessageId must not be blank");
        }
        text = text == null ? "" : text;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public boolean hasText() {
        return !text.isBlank();
    }
}
