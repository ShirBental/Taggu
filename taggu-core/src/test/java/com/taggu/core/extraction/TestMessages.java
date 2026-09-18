package com.taggu.core.extraction;

import com.taggu.core.model.CanonicalIds;
import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import com.taggu.core.model.MessageType;
import com.taggu.core.model.Participant;
import com.taggu.core.model.SourceType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds canonical messages without going through any source adapter. */
public final class TestMessages {

    public static final SourceType SOURCE = SourceType.OTHER;
    public static final String CONVERSATION_KEY = "test-conversation";

    private TestMessages() {}

    public static CanonicalMessage message(String id, Participant sender, Instant sentAt, String text) {
        return new CanonicalMessage(
                CanonicalIds.messageId(SOURCE, CONVERSATION_KEY, id),
                SOURCE,
                id,
                CanonicalIds.conversationId(SOURCE, CONVERSATION_KEY),
                sender == null ? null : sender.id(),
                sentAt,
                MessageType.TEXT,
                text,
                null,
                Map.of(),
                text);
    }

    /** A conversation where each text is one minute after the previous one. */
    public static List<CanonicalMessage> sequence(Participant sender, Instant start, String... texts) {
        List<CanonicalMessage> messages = new ArrayList<>();
        for (int index = 0; index < texts.length; index++) {
            messages.add(message("m" + index, sender, start.plus(index, ChronoUnit.MINUTES), texts[index]));
        }
        return messages;
    }

    public static Conversation conversation(List<Participant> participants) {
        return Conversation.of(SOURCE, CONVERSATION_KEY, "Test conversation", participants, null, null);
    }

    public static Participant participant(String name) {
        return Participant.of(SOURCE, name, name);
    }
}
