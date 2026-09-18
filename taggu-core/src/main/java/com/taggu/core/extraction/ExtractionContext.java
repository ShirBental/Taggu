package com.taggu.core.extraction;

import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import com.taggu.core.model.Participant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One window of a conversation, handed to a {@link KnowledgeExtractor}.
 *
 * <p>Messages cannot be read one at a time. "Want to come Sunday?" / "Sure" / "Around 4?" /
 * "Perfect" is one event spread over four messages, so an extractor is always given a window rather
 * than a message.
 *
 * <p>The window has two parts. {@code leadIn} is earlier context the extractor may read to interpret
 * what follows but must not attribute knowledge to on its own. {@code focus} is what this window is
 * responsible for: at least one item of extracted knowledge must cite a focus message, which is what
 * stops overlapping windows from producing the same item twice.
 *
 * <p>{@code zone} is the local time the participants were speaking in. "Sunday around 4" cannot be
 * turned into an instant without it, and resolving such a phrase in UTC would quietly move events
 * across days.
 */
public record ExtractionContext(
        Conversation conversation, List<CanonicalMessage> leadIn, List<CanonicalMessage> focus, ZoneId zone) {

    public ExtractionContext {
        Objects.requireNonNull(conversation, "conversation");
        Objects.requireNonNull(zone, "zone");
        leadIn = leadIn == null ? List.of() : List.copyOf(leadIn);
        if (focus == null || focus.isEmpty()) {
            throw new IllegalArgumentException("an extraction window must have at least one focus message");
        }
        focus = List.copyOf(focus);
    }

    public ExtractionContext(Conversation conversation, List<CanonicalMessage> leadIn, List<CanonicalMessage> focus) {
        this(conversation, leadIn, focus, ZoneId.of("UTC"));
    }

    /** Lead-in followed by focus, in chronological order: everything the extractor may read. */
    public List<CanonicalMessage> allMessages() {
        List<CanonicalMessage> all = new ArrayList<>(leadIn.size() + focus.size());
        all.addAll(leadIn);
        all.addAll(focus);
        return List.copyOf(all);
    }

    /** True when the message may be cited as evidence by knowledge produced from this window. */
    public boolean isInFocus(UUID messageId) {
        return focus.stream().anyMatch(message -> message.id().equals(messageId));
    }

    /** The display name of a participant id, falling back to {@code null} when unknown. */
    public String displayNameOf(UUID participantId) {
        if (participantId == null) {
            return null;
        }
        return conversation.participants().stream()
                .filter(participant -> participant.id().equals(participantId))
                .map(Participant::displayName)
                .findFirst()
                .orElse(null);
    }
}
