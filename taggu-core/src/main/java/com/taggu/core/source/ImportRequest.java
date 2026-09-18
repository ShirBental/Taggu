package com.taggu.core.source;

import java.time.ZoneId;
import java.util.Objects;

/**
 * What the caller knows about an import that the exported file itself does not say.
 *
 * @param sourceConversationId a stable key for the thread, normally derived from the export file
 * @param title a human-readable name for the thread
 * @param zone the zone the export's wall-clock timestamps should be read in. Chat exports record
 *     local time with no offset, so the zone has to come from outside the file; guessing it silently
 *     would shift every timestamp.
 * @param defaultLocaleHint an optional hint such as {@code "en-US"} or {@code "he-IL"} used to break
 *     ties between ambiguous date formats. May be {@code null}.
 */
public record ImportRequest(String sourceConversationId, String title, ZoneId zone, String defaultLocaleHint) {

    public ImportRequest {
        Objects.requireNonNull(zone, "zone");
        if (sourceConversationId == null || sourceConversationId.isBlank()) {
            throw new IllegalArgumentException("sourceConversationId must not be blank");
        }
        title = title == null || title.isBlank() ? sourceConversationId : title.trim();
    }

    public static ImportRequest of(String sourceConversationId, String title, ZoneId zone) {
        return new ImportRequest(sourceConversationId, title, zone, null);
    }
}
