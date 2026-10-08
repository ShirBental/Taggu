package com.taggu.app.importer;

import com.taggu.app.config.TagguProperties;
import com.taggu.app.importer.ImportSummary.ParseIssueView;
import com.taggu.core.extraction.ConversationWindower;
import com.taggu.core.extraction.ExtractionContext;
import com.taggu.core.extraction.ExtractionValidator;
import com.taggu.core.extraction.KnowledgeExtractor;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.source.ImportRequest;
import com.taggu.core.source.MessageSourceAdapter;
import com.taggu.core.source.ParseIssue;
import com.taggu.core.source.ParsedConversation;
import com.taggu.persistence.ConversationStore;
import com.taggu.persistence.ImportRunStore;
import com.taggu.persistence.KnowledgeStore;
import java.io.IOException;
import java.io.Reader;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs one import from end to end: parse, store, extract, store knowledge, record the run.
 *
 * <p>The service is written against {@link MessageSourceAdapter} and {@link KnowledgeExtractor}, so
 * it has no idea which source it is importing or how knowledge is being found. Adding Gmail adds an
 * adapter; it does not change this class.
 *
 * <p>Logging here is counts only. Message text, sender names and unparseable lines never reach a log
 * statement, at any level.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ConversationStore conversationStore;
    private final KnowledgeStore knowledgeStore;
    private final ImportRunStore importRunStore;
    private final KnowledgeExtractor extractor;
    private final ExtractionValidator validator;
    private final TagguProperties properties;

    public ImportService(
            ConversationStore conversationStore,
            KnowledgeStore knowledgeStore,
            ImportRunStore importRunStore,
            KnowledgeExtractor extractor,
            ExtractionValidator validator,
            TagguProperties properties) {
        this.conversationStore = conversationStore;
        this.knowledgeStore = knowledgeStore;
        this.importRunStore = importRunStore;
        this.extractor = extractor;
        this.validator = validator;
        this.properties = properties;
    }

    /**
     * Imports one export.
     *
     * @param adapter the source adapter that understands the file
     * @param reader the export's contents
     * @param options what the caller knows that the file does not say
     */
    public ImportSummary importExport(MessageSourceAdapter adapter, Reader reader, ImportOptions options) {
        ZoneId zone = options.zone() == null ? properties.defaultZone() : options.zone();
        ImportRequest request = new ImportRequest(
                options.sourceConversationId(), options.title(), zone, options.localeHint());

        UUID runId = importRunStore.start(adapter.source().name(), options.sourceReference());
        try {
            ParsedConversation parsed = adapter.parse(reader, request);
            if (parsed.messages().isEmpty()) {
                // Not an export, or not one this adapter can read. Storing it would leave an empty
                // conversation behind for every mistaken upload.
                throw new ImportRejectedException("no messages could be read from the file");
            }
            ConversationStore.StoredConversation stored = conversationStore.save(parsed);

            Extracted extracted =
                    properties.extraction().enabled() ? extractKnowledge(parsed, zone) : new Extracted(0, 0);
            int knowledgeItems = extracted.stored();

            importRunStore.succeed(
                    runId,
                    stored.conversationId(),
                    stored.parsedMessages(),
                    stored.newMessages(),
                    parsed.issues().size(),
                    knowledgeItems);

            long skipped = parsed.issues().stream()
                    .filter(issue -> issue.severity() == ParseIssue.Severity.SKIPPED)
                    .count();
            log.info(
                    "Imported {} conversation: {} messages parsed, {} new, {} lines skipped, "
                            + "{} other parse issues, {} knowledge items, {} rejected by validation",
                    adapter.source(),
                    stored.parsedMessages(),
                    stored.newMessages(),
                    skipped,
                    parsed.issues().size() - skipped,
                    knowledgeItems,
                    extracted.rejected());
            parsed.issues().forEach(issue -> log.debug("Import issue: {}", issue.summary()));

            return new ImportSummary(
                    stored.conversationId(),
                    adapter.source().name(),
                    parsed.conversation().title(),
                    stored.parsedMessages(),
                    stored.newMessages(),
                    knowledgeItems,
                    extracted.rejected(),
                    parsed.issues().size(),
                    issueViews(parsed.issues(), options.includeRawLines()));
        } catch (IOException ex) {
            importRunStore.fail(runId);
            throw new ImportFailedException("the export could not be read", ex);
        } catch (RuntimeException ex) {
            importRunStore.fail(runId);
            throw ex;
        }
    }

    /**
     * Extracts window by window, and lets nothing through to the store that the validator has not
     * checked against the window it came from.
     */
    private Extracted extractKnowledge(ParsedConversation parsed, ZoneId zone) {
        ConversationWindower windower = new ConversationWindower(
                properties.extraction().focusWindowSize(), properties.extraction().leadInSize(), zone);
        List<ExtractionContext> windows = windower.windows(parsed.conversation(), parsed.messages());
        List<ExtractionResult> results = new ArrayList<>(windows.size());
        int rejected = 0;
        for (ExtractionContext window : windows) {
            ExtractionValidator.Validated validated = validator.validate(window, extractor.extract(window));
            results.add(validated.result());
            rejected += validated.rejected();
        }
        ExtractionResult merged = ExtractionResult.merge(extractor.name(), results);
        knowledgeStore.save(parsed.conversation().id(), merged);
        return new Extracted(merged.size(), rejected);
    }

    private record Extracted(int stored, int rejected) {}

    private List<ParseIssueView> issueViews(List<ParseIssue> issues, boolean includeRawLines) {
        return issues.stream()
                .map(issue -> new ParseIssueView(
                        issue.lineNumber(),
                        issue.severity().name(),
                        issue.reason(),
                        includeRawLines ? issue.rawLine() : null))
                .toList();
    }

    /**
     * Raised when a file was read but held nothing to import. The message is written for the caller
     * and never quotes the file.
     */
    public static class ImportRejectedException extends RuntimeException {
        public ImportRejectedException(String message) {
            super(message);
        }
    }

    /** Raised when an export cannot be read at all, as opposed to a line that cannot be parsed. */
    public static class ImportFailedException extends RuntimeException {
        public ImportFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Caller-supplied details of an import.
     *
     * @param sourceReference a file name, kept for the import log; never its contents
     * @param includeRawLines whether unreadable lines are echoed back in the response
     */
    public record ImportOptions(
            String sourceConversationId,
            String title,
            ZoneId zone,
            String localeHint,
            String sourceReference,
            boolean includeRawLines) {}
}
