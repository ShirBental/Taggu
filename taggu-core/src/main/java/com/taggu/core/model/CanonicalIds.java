package com.taggu.core.model;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Derives canonical identifiers from source identity.
 *
 * <p>Ids are a pure function of the source coordinates, so importing the same export twice produces
 * the same ids and the import stays idempotent. Provenance therefore keeps pointing at the same
 * messages across re-imports.
 */
public final class CanonicalIds {

    /** ASCII unit separator, chosen because it cannot occur in a sensible identifier. */
    private static final char SEP = 31;

    private CanonicalIds() {}

    public static UUID conversationId(SourceType source, String sourceConversationId) {
        return derive("conversation", source.name(), sourceConversationId);
    }

    public static UUID participantId(SourceType source, String sourceIdentifier) {
        return derive("participant", source.name(), sourceIdentifier);
    }

    public static UUID messageId(SourceType source, String sourceConversationId, String sourceMessageId) {
        return derive("message", source.name(), sourceConversationId + SEP + sourceMessageId);
    }

    private static UUID derive(String kind, String source, String identity) {
        String key = kind + SEP + source + SEP + identity;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
