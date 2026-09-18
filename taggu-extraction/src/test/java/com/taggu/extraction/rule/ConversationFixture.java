package com.taggu.extraction.rule;

import com.taggu.core.extraction.ConversationWindower;
import com.taggu.core.extraction.ExtractionContext;
import com.taggu.core.model.CanonicalIds;
import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import com.taggu.core.model.MessageType;
import com.taggu.core.model.Participant;
import com.taggu.core.model.SourceType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds conversations for the extractor tests.
 *
 * <p>Deliberately not built through a source adapter: the extractor must work on canonical messages
 * alone, and the source used here is {@code OTHER} to make that concrete.
 */
final class ConversationFixture {

    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    /** A Thursday, so "Sunday" resolves three days later. */
    static final LocalDateTime START = LocalDateTime.of(2024, 3, 14, 18, 2);

    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final List<CanonicalMessage> messages = new ArrayList<>();

    ConversationFixture say(String sender, String text) {
        Participant participant =
                participants.computeIfAbsent(sender, name -> Participant.of(SourceType.OTHER, name, name));
        String id = "m" + messages.size();
        Instant sentAt = START.atZone(ZONE).toInstant().plus(messages.size(), ChronoUnit.MINUTES);
        messages.add(new CanonicalMessage(
                CanonicalIds.messageId(SourceType.OTHER, "fixture", id),
                SourceType.OTHER,
                id,
                CanonicalIds.conversationId(SourceType.OTHER, "fixture"),
                participant.id(),
                sentAt,
                MessageType.TEXT,
                text,
                null,
                Map.of(),
                text));
        return this;
    }

    Conversation conversation() {
        return Conversation.of(
                SourceType.OTHER, "fixture", "Fixture", List.copyOf(participants.values()), null, null);
    }

    List<CanonicalMessage> messages() {
        return List.copyOf(messages);
    }

    CanonicalMessage message(int index) {
        return messages.get(index);
    }

    /** One window holding the whole fixture, which is how a short conversation is extracted. */
    ExtractionContext singleWindow() {
        return new ExtractionContext(conversation(), List.of(), messages(), ZONE);
    }

    List<ExtractionContext> windows(int focusSize, int leadInSize) {
        return new ConversationWindower(focusSize, leadInSize, ZONE).windows(conversation(), messages());
    }
}
