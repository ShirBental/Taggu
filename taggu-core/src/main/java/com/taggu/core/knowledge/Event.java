package com.taggu.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/**
 * Something happening at a specific or approximate time.
 *
 * <p>Conversations rarely state a time exactly once. {@code timeExpression} keeps the words the time
 * was given in ("Sunday around 4") so a later extractor can re-resolve it without re-reading the
 * conversation, and {@link TimePrecision} records how firm the resolved instant is.
 *
 * @param title what is happening
 * @param startsAt resolved start, or {@code null} when the time could not be resolved
 * @param endsAt resolved end, when one was given
 * @param precision how firm the resolved time is
 * @param timeExpression the time as the conversation phrased it
 * @param location where it happens, when stated
 * @param confidence how sure the extractor is, from 0 to 1
 * @param provenance the messages the event was assembled from
 */
public record Event(
        String title,
        Instant startsAt,
        Instant endsAt,
        TimePrecision precision,
        String timeExpression,
        String location,
        double confidence,
        Provenance provenance) {

    public Event {
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(precision, "precision");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        title = title.trim();
        confidence = Confidence.require(confidence);
    }
}
