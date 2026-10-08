package com.taggu.core.extraction;

import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Slices a conversation into the windows an extractor reads.
 *
 * <p>Each window owns a block of {@code focusSize} messages and is preceded by up to
 * {@code leadInSize} earlier messages for interpretation only. Focus blocks do not overlap, so every
 * message is the responsibility of exactly one window and knowledge is not extracted twice.
 *
 * <p>Messages are put in time order first. Those sharing a timestamp keep the order they arrive in,
 * so callers should pass them in source order: a source that records time to the minute relies on it.
 */
public final class ConversationWindower {

    public static final int DEFAULT_FOCUS_SIZE = 8;
    public static final int DEFAULT_LEAD_IN_SIZE = 4;

    private final int focusSize;
    private final int leadInSize;
    private final ZoneId zone;

    public ConversationWindower() {
        this(DEFAULT_FOCUS_SIZE, DEFAULT_LEAD_IN_SIZE, ZoneId.of("UTC"));
    }

    public ConversationWindower(ZoneId zone) {
        this(DEFAULT_FOCUS_SIZE, DEFAULT_LEAD_IN_SIZE, zone);
    }

    public ConversationWindower(int focusSize, int leadInSize, ZoneId zone) {
        if (focusSize < 1) {
            throw new IllegalArgumentException("focusSize must be at least 1");
        }
        if (leadInSize < 0) {
            throw new IllegalArgumentException("leadInSize must not be negative");
        }
        this.focusSize = focusSize;
        this.leadInSize = leadInSize;
        this.zone = java.util.Objects.requireNonNull(zone, "zone");
    }

    public List<ExtractionContext> windows(Conversation conversation, List<CanonicalMessage> messages) {
        List<CanonicalMessage> ordered = new ArrayList<>(messages);
        // A stable sort on time alone: messages with the same timestamp stay in the order they were
        // given, which is the order of the source. Breaking the tie on sourceMessageId, as this once
        // did, shuffled them, because for sources without real ids that id is a hash.
        ordered.sort(Comparator.comparing(CanonicalMessage::sentAt));

        List<ExtractionContext> windows = new ArrayList<>();
        for (int start = 0; start < ordered.size(); start += focusSize) {
            int end = Math.min(start + focusSize, ordered.size());
            List<CanonicalMessage> leadIn = ordered.subList(Math.max(0, start - leadInSize), start);
            windows.add(new ExtractionContext(conversation, leadIn, ordered.subList(start, end), zone));
        }
        return windows;
    }
}
