package com.taggu.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A person taking part in a conversation.
 *
 * @param id canonical identifier, derived deterministically from {@code source} and
 *     {@code sourceIdentifier} so that re-importing the same export yields the same participant
 * @param source the system the participant was seen in
 * @param sourceIdentifier how that system names them (a phone number, an address, a handle)
 * @param displayName the human-readable name, which may change over time
 */
public record Participant(UUID id, SourceType source, String sourceIdentifier, String displayName) {

    public Participant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        requireText(sourceIdentifier, "sourceIdentifier");
        requireText(displayName, "displayName");
    }

    /** Builds a participant whose id is derived from its source identity, so imports are idempotent. */
    public static Participant of(SourceType source, String sourceIdentifier, String displayName) {
        return new Participant(
                CanonicalIds.participantId(source, sourceIdentifier), source, sourceIdentifier, displayName);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
