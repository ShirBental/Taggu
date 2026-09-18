package com.taggu.core.knowledge;

import java.util.Objects;

/**
 * A durable piece of information stated in a conversation, for example "the contractor quoted $1,550
 * for the roof repair".
 *
 * @param statement the fact in one sentence
 * @param subject what the fact is about, when the extractor can name it
 * @param confidence how sure the extractor is, from 0 to 1
 * @param provenance the messages that support the statement
 */
public record Fact(String statement, String subject, double confidence, Provenance provenance) {

    public Fact {
        Objects.requireNonNull(provenance, "provenance");
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("statement must not be blank");
        }
        statement = statement.trim();
        confidence = Confidence.require(confidence);
    }
}
