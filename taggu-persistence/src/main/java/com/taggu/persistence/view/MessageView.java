package com.taggu.persistence.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stored message, with the tags extraction put on it. */
public record MessageView(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String senderName,
        Instant sentAt,
        String messageType,
        String text,
        List<String> tags) {}
