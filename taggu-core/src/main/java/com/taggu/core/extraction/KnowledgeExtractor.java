package com.taggu.core.extraction;

import com.taggu.core.knowledge.ExtractionResult;

/**
 * Turns a window of canonical messages into structured knowledge.
 *
 * <p>Implementations see {@link com.taggu.core.model.CanonicalMessage} and nothing else. They have no
 * way of telling whether the conversation came from WhatsApp, Gmail or SMS, which is what lets new
 * sources be added without touching extraction.
 *
 * <p>The interface is written so that a rule-based implementation and a future model-backed one are
 * interchangeable: one window in, one {@link ExtractionResult} out, no provider-specific types.
 */
public interface KnowledgeExtractor {

    /** Stable name recorded on everything this extractor produces. */
    String name();

    /**
     * Extracts knowledge from one window.
     *
     * <p>Implementations must cite provenance for every item they return, and must only cite messages
     * present in the window.
     */
    ExtractionResult extract(ExtractionContext context);
}
