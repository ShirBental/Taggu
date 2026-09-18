package com.taggu.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/**
 * Something someone needs to do.
 *
 * @param description what needs doing
 * @param assignee who is expected to do it, as named in the conversation, or {@code null}
 * @param dueAt resolved deadline, or {@code null} when none was given or it could not be resolved
 * @param duePrecision how firm the deadline is
 * @param dueExpression the deadline as the conversation phrased it
 * @param status where the item stands
 * @param confidence how sure the extractor is, from 0 to 1
 * @param provenance the messages the item was taken from
 */
public record ActionItem(
        String description,
        String assignee,
        Instant dueAt,
        TimePrecision duePrecision,
        String dueExpression,
        ActionItemStatus status,
        double confidence,
        Provenance provenance) {

    public ActionItem {
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duePrecision, "duePrecision");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        description = description.trim();
        confidence = Confidence.require(confidence);
    }
}
