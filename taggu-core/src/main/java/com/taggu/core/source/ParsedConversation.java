package com.taggu.core.source;

import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import java.util.List;
import java.util.Objects;

/**
 * The canonical result of reading one export: everything downstream needs, and nothing about the
 * format it came from.
 *
 * @param conversation the thread the messages belong to
 * @param messages the messages, in chronological order
 * @param issues lines that could not be parsed, or were parsed with an assumption
 */
public record ParsedConversation(
        Conversation conversation, List<CanonicalMessage> messages, List<ParseIssue> issues) {

    public ParsedConversation {
        Objects.requireNonNull(conversation, "conversation");
        messages = messages == null ? List.of() : List.copyOf(messages);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
