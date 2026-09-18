package com.taggu.persistence;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Derives ids for rows that have a natural key.
 *
 * <p>Extraction is expected to be re-run: a better extractor, a corrected rule, a re-import. Deriving
 * a row's id from what the row actually is means re-running produces the same id, so the second run
 * updates rather than duplicating, without a de-duplication pass anywhere.
 */
final class StoredIds {

    private static final char SEP = 31;

    private StoredIds() {}

    static UUID tagId(String slug) {
        return derive("tag", slug);
    }

    static UUID entityId(String type, String normalizedName) {
        return derive("entity", type + SEP + normalizedName);
    }

    /** A knowledge row is identified by its conversation, its extractor, its text and its evidence. */
    static UUID knowledgeId(String kind, UUID conversationId, String extractor, String text, List<UUID> sources) {
        StringBuilder key = new StringBuilder(conversationId.toString())
                .append(SEP)
                .append(extractor)
                .append(SEP)
                .append(text);
        sources.stream().map(UUID::toString).sorted().forEach(id -> key.append(SEP).append(id));
        return derive(kind, key.toString());
    }

    private static UUID derive(String kind, String identity) {
        return UUID.nameUUIDFromBytes((kind + SEP + identity).getBytes(StandardCharsets.UTF_8));
    }
}
