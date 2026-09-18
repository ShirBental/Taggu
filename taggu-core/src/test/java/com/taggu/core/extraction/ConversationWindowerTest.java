package com.taggu.core.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import com.taggu.core.model.Participant;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConversationWindowerTest {

    private static final Participant SENDER = TestMessages.participant("Dana");
    private static final Instant START = Instant.parse("2024-03-14T17:00:00Z");

    @Test
    @DisplayName("every message is in the focus of exactly one window")
    void focusBlocksDoNotOverlap() {
        List<CanonicalMessage> messages = TestMessages.sequence(SENDER, START, "a", "b", "c", "d", "e", "f", "g");
        Conversation conversation = TestMessages.conversation(List.of(SENDER));

        List<ExtractionContext> windows = new ConversationWindower(3, 2, ZoneId.of("UTC"))
                .windows(conversation, messages);

        assertThat(windows).hasSize(3);
        List<String> focused = windows.stream()
                .flatMap(window -> window.focus().stream())
                .map(CanonicalMessage::text)
                .toList();
        assertThat(focused).containsExactly("a", "b", "c", "d", "e", "f", "g").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a window can read earlier messages without being allowed to attribute knowledge to them")
    void leadInIsReadableButNotInFocus() {
        List<CanonicalMessage> messages = TestMessages.sequence(SENDER, START, "a", "b", "c", "d", "e");
        Conversation conversation = TestMessages.conversation(List.of(SENDER));

        List<ExtractionContext> windows = new ConversationWindower(2, 2, ZoneId.of("UTC"))
                .windows(conversation, messages);

        ExtractionContext second = windows.get(1);
        assertThat(second.leadIn()).extracting(CanonicalMessage::text).containsExactly("a", "b");
        assertThat(second.focus()).extracting(CanonicalMessage::text).containsExactly("c", "d");
        assertThat(second.allMessages()).extracting(CanonicalMessage::text).containsExactly("a", "b", "c", "d");
        assertThat(second.isInFocus(messages.get(0).id())).isFalse();
        assertThat(second.isInFocus(messages.get(2).id())).isTrue();
    }

    @Test
    @DisplayName("messages are windowed in time order whatever order they arrive in")
    void sortsMessagesBeforeWindowing() {
        List<CanonicalMessage> messages = TestMessages.sequence(SENDER, START, "a", "b", "c");
        Conversation conversation = TestMessages.conversation(List.of(SENDER));

        List<ExtractionContext> windows = new ConversationWindower(3, 0, ZoneId.of("UTC"))
                .windows(conversation, List.of(messages.get(2), messages.get(0), messages.get(1)));

        assertThat(windows).singleElement().satisfies(window ->
                assertThat(window.focus()).extracting(CanonicalMessage::text).containsExactly("a", "b", "c"));
    }
}
