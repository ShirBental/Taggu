package com.taggu.core.source;

/**
 * A line the parser could not turn into a message.
 *
 * <p>A malformed line never fails an import: it is reported here and the rest of the file is still
 * read. {@code rawLine} is kept so the line can be looked at afterwards, and is treated as message
 * content, so it is returned to the local caller but never written to a log.
 *
 * @param lineNumber 1-based line number in the export
 * @param severity how much of the file was affected
 * @param reason a short, content-free explanation
 * @param rawLine the offending line, or {@code null} when it is not useful
 */
public record ParseIssue(int lineNumber, Severity severity, String reason, String rawLine) {

    public enum Severity {
        /** The line was read but something about it was assumed. */
        WARNING,
        /** The line was skipped. */
        SKIPPED
    }

    public static ParseIssue skipped(int lineNumber, String reason, String rawLine) {
        return new ParseIssue(lineNumber, Severity.SKIPPED, reason, rawLine);
    }

    public static ParseIssue warning(int lineNumber, String reason, String rawLine) {
        return new ParseIssue(lineNumber, Severity.WARNING, reason, rawLine);
    }

    /** A description safe to log: line number and reason only, never the line itself. */
    public String summary() {
        return "line " + lineNumber + ": " + reason;
    }
}
