package com.taggu.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A thread of messages between participants.
 *
 * @param id canonical identifier, derived from {@code source} and {@code sourceConversationId}
 * @param source the system the conversation came from
 * @param sourceConversationId how that system identifies the thread; for a file-based export this is
 *     typically a stable key derived from the export itself
 * @param title a human-readable name for the thread
 * @param participants everyone seen speaking in the thread
 * @param startedAt timestamp of the first message, or {@code null} when the thread is empty
 * @param endedAt timestamp of the last message, or {@code null} when the thread is empty
 */
public record Conversation(
        UUID id,
        SourceType source,
        String sourceConversationId,
        String title,
        List<Participant> participants,
        Instant startedAt,
        Instant endedAt) {

    public Conversation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        if (sourceConversationId == null || sourceConversationId.isBlank()) {
            throw new IllegalArgumentException("sourceConversationId must not be blank");
        }
        participants = participants == null ? List.of() : List.copyOf(participants);
    }

    public static Conversation of(
            SourceType source,
            String sourceConversationId,
            String title,
            List<Participant> participants,
            Instant startedAt,
            Instant endedAt) {
        return new Conversation(
                CanonicalIds.conversationId(source, sourceConversationId),
                source,
                sourceConversationId,
                title,
                participants,
                startedAt,
                endedAt);
    }
}
