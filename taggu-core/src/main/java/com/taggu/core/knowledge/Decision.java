package com.taggu.core.knowledge;

import java.time.Instant;
import java.util.Objects;

/**
 * A decision the participants reached.
 *
 * @param statement the decision in one sentence
 * @param decidedAt when it was settled, normally the timestamp of the message that settled it
 * @param confidence how sure the extractor is, from 0 to 1
 * @param provenance the messages that show the decision being made
 */
public record Decision(String statement, Instant decidedAt, double confidence, Provenance provenance) {

    public Decision {
        Objects.requireNonNull(provenance, "provenance");
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("statement must not be blank");
        }
        statement = statement.trim();
        confidence = Confidence.require(confidence);
    }
}
