package com.taggu.persistence.view;

import java.time.Instant;
import java.util.UUID;

/** One message matching a search, with just enough context to be readable on its own. */
public record SearchHit(
        UUID messageId,
        UUID conversationId,
        String conversationTitle,
        String senderName,
        Instant sentAt,
        String text) {}
