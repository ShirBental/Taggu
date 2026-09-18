package com.taggu.persistence;

import com.taggu.core.model.CanonicalIds;
import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import com.taggu.core.model.MessageType;
import com.taggu.core.model.Participant;
import com.taggu.core.model.SourceType;
import com.taggu.core.source.ParseIssue;
import com.taggu.core.source.ParsedConversation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds parsed conversations without going near a source adapter. */
final class StoreFixtures {

    static final SourceType SOURCE = SourceType.OTHER;
    static final Instant START = Instant.parse("2024-03-14T17:02:00Z");

    private final String key;
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final List<CanonicalMessage> messages = new ArrayList<>();

    StoreFixtures(String key) {
        this.key = key;
    }

    StoreFixtures say(String sender, String text) {
        Participant participant = participants.computeIfAbsent(sender, name -> Participant.of(SOURCE, name, name));
        String sourceMessageId = "m" + messages.size();
        messages.add(new CanonicalMessage(
                CanonicalIds.messageId(SOURCE, key, sourceMessageId),
                SOURCE,
                sourceMessageId,
                CanonicalIds.conversationId(SOURCE, key),
                participant.id(),
                START.plus(messages.size(), ChronoUnit.MINUTES),
                MessageType.TEXT,
                text,
                null,
                Map.of("lineNumber", Integer.toString(messages.size() + 1)),
                text));
        return this;
    }

    ParsedConversation parsed() {
        Conversation conversation = Conversation.of(
                SOURCE,
                key,
                "Fixture " + key,
                List.copyOf(participants.values()),
                messages.isEmpty() ? null : messages.get(0).sentAt(),
                messages.isEmpty() ? null : messages.get(messages.size() - 1).sentAt());
        return new ParsedConversation(conversation, List.copyOf(messages), List.<ParseIssue>of());
    }

    CanonicalMessage message(int index) {
        return messages.get(index);
    }
}
