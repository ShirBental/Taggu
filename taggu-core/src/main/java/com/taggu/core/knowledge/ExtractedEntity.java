package com.taggu.core.knowledge;

import java.util.Locale;
import java.util.Objects;

/**
 * A person, organisation, place or product referred to in a conversation.
 *
 * @param type what kind of thing it is
 * @param name the surface form as written
 * @param normalizedName a lower-cased, whitespace-collapsed form used for matching across messages
 * @param confidence how sure the extractor is, from 0 to 1
 * @param provenance the messages the entity was mentioned in
 */
public record ExtractedEntity(
        EntityType type, String name, String normalizedName, double confidence, Provenance provenance) {

    public ExtractedEntity {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(provenance, "provenance");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        name = name.trim();
        normalizedName = normalizedName == null || normalizedName.isBlank() ? normalize(name) : normalizedName;
        confidence = Confidence.require(confidence);
    }

    public static ExtractedEntity of(EntityType type, String name, double confidence, Provenance provenance) {
        return new ExtractedEntity(type, name, null, confidence, provenance);
    }

    public static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
