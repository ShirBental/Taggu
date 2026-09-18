package com.taggu.core.source;

import com.taggu.core.model.SourceType;
import java.io.IOException;
import java.io.Reader;

/**
 * Reads one export format and produces canonical messages.
 *
 * <p>This is the boundary of format knowledge in the system. An adapter is the only kind of class
 * allowed to know what a WhatsApp line, an mbox file or a Slack export looks like; everything above
 * it sees {@link ParsedConversation} and cannot tell the difference.
 */
public interface MessageSourceAdapter {

    /** The source this adapter reads. */
    SourceType source();

    /**
     * Reads an export.
     *
     * <p>Implementations tolerate malformed input: a line that cannot be read is reported as a
     * {@link ParseIssue} and parsing continues. An {@link IOException} means the stream itself failed.
     */
    ParsedConversation parse(Reader reader, ImportRequest request) throws IOException;
}
