package com.taggu.persistence.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A conversation with its headline counts, for the conversation list. */
public record ConversationSummary(
        UUID id,
        String source,
        String sourceConversationId,
        String title,
        Instant startedAt,
        Instant endedAt,
        Instant importedAt,
        long messageCount,
        List<ParticipantView> participants) {}
