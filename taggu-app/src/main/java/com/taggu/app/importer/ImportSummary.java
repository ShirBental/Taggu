package com.taggu.app.importer;

import java.util.List;
import java.util.UUID;

/**
 * What an import did, as the API reports it.
 *
 * @param parsedMessages messages read from the file
 * @param newMessages messages actually stored; a re-import of the same export stores none
 * @param issues lines that could not be read. The offending line is included only when the caller
 *     asks for it, because it is message content like any other.
 */
public record ImportSummary(
        UUID conversationId,
        String source,
        String title,
        int parsedMessages,
        int newMessages,
        int knowledgeItems,
        int parseIssueCount,
        List<ParseIssueView> issues) {

    /** A line the parser could not use. */
    public record ParseIssueView(int lineNumber, String severity, String reason, String rawLine) {}
}
