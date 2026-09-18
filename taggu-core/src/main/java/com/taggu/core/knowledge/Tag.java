package com.taggu.core.knowledge;

import java.util.Locale;
import java.util.Objects;

/**
 * A broad classification of the messages it cites, such as {@code school}, {@code travel} or
 * {@code purchase}.
 *
 * <p>A tag says what a conversation is about. It is deliberately not knowledge: anything with content
 * of its own belongs in a {@link Fact}, {@link Event}, {@link ActionItem} or {@link Decision}.
 *
 * @param slug stable machine-readable name, lower-case and hyphenated
 * @param label human-readable name
 * @param confidence how sure the extractor is, from 0 to 1
 * @param provenance the messages that carry the tag
 */
public record Tag(String slug, String label, double confidence, Provenance provenance) {

    public Tag {
        Objects.requireNonNull(provenance, "provenance");
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        slug = slug.trim().toLowerCase(Locale.ROOT);
        label = label == null || label.isBlank() ? slug : label.trim();
        confidence = Confidence.require(confidence);
    }

    public static Tag of(String slug, double confidence, Provenance provenance) {
        return new Tag(slug, null, confidence, provenance);
    }
}
