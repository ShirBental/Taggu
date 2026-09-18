package com.taggu.ingest.whatsapp;

import com.taggu.core.model.CanonicalIds;
import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import com.taggu.core.model.MessageType;
import com.taggu.core.model.Participant;
import com.taggu.core.model.SourceType;
import com.taggu.core.source.ImportRequest;
import com.taggu.core.source.MessageSourceAdapter;
import com.taggu.core.source.ParseIssue;
import com.taggu.core.source.ParsedConversation;
import com.taggu.ingest.whatsapp.WhatsAppLineParser.SenderAndText;
import com.taggu.ingest.whatsapp.WhatsAppLineParser.WhatsAppHeader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads a standard WhatsApp {@code .txt} export into canonical messages.
 *
 * <p>The file is read in two passes. The first collects the date strings so the export's day/month
 * order can be settled from evidence rather than guessed (see {@link WhatsAppTimestamps}); the second
 * builds the messages. Exports are single conversations of modest size, so both passes work on the
 * lines held in memory.
 *
 * <p>Nothing here throws on bad input. A line that cannot be read becomes a {@link ParseIssue} and the
 * import carries on, because one corrupted line in a year of conversation should not cost the year.
 *
 * <p>This class, and only this class, knows what a WhatsApp line looks like. Everything downstream
 * receives {@link ParsedConversation}.
 */
public final class WhatsAppTextExportAdapter implements MessageSourceAdapter {

    private static final SourceType SOURCE = SourceType.WHATSAPP;

    @Override
    public SourceType source() {
        return SOURCE;
    }

    @Override
    public ParsedConversation parse(Reader reader, ImportRequest request) throws IOException {
        List<String> rawLines = readLines(reader);
        List<ParseIssue> issues = new ArrayList<>();

        WhatsAppDateOrder order = detectOrder(rawLines, request, issues);
        UUID conversationId = CanonicalIds.conversationId(SOURCE, request.sourceConversationId());

        List<CanonicalMessage> messages = new ArrayList<>();
        Map<String, Participant> participants = new LinkedHashMap<>();
        Map<String, Integer> idCollisions = new HashMap<>();
        Draft draft = null;

        for (int index = 0; index < rawLines.size(); index++) {
            int lineNumber = index + 1;
            String raw = rawLines.get(index);
            String normalized = WhatsAppLineParser.normalize(raw);
            Optional<WhatsAppHeader> header = WhatsAppLineParser.readHeader(normalized);

            if (header.isEmpty()) {
                if (draft == null) {
                    if (!normalized.isBlank()) {
                        issues.add(ParseIssue.skipped(lineNumber, "line before the first message header", raw));
                    }
                    continue;
                }
                draft.append(normalized, raw);
                continue;
            }

            if (draft != null) {
                messages.add(draft.toMessage(conversationId, request, idCollisions));
            }
            draft = startDraft(header.get(), request, order, participants, lineNumber, raw, issues);
            if (draft == null) {
                continue;
            }
        }
        if (draft != null) {
            messages.add(draft.toMessage(conversationId, request, idCollisions));
        }

        Conversation conversation = new Conversation(
                conversationId,
                SOURCE,
                request.sourceConversationId(),
                request.title(),
                List.copyOf(participants.values()),
                messages.isEmpty() ? null : messages.get(0).sentAt(),
                messages.isEmpty() ? null : messages.get(messages.size() - 1).sentAt());

        return new ParsedConversation(conversation, messages, issues);
    }

    private Draft startDraft(
            WhatsAppHeader header,
            ImportRequest request,
            WhatsAppDateOrder order,
            Map<String, Participant> participants,
            int lineNumber,
            String raw,
            List<ParseIssue> issues) {

        Optional<LocalDateTime> localDateTime =
                WhatsAppTimestamps.parse(header.date(), header.time(), header.meridiem(), order);
        if (localDateTime.isEmpty()) {
            issues.add(ParseIssue.skipped(lineNumber, "unreadable timestamp in message header", raw));
            return null;
        }
        Instant sentAt = localDateTime.get().atZone(request.zone()).toInstant();

        Optional<SenderAndText> split = WhatsAppLineParser.splitSender(header.body());
        if (split.isEmpty()) {
            // No sender means WhatsApp itself wrote the line: a join, a leave, an encryption notice.
            return new Draft(lineNumber, sentAt, null, MessageType.SYSTEM, header.body(), raw);
        }

        SenderAndText senderAndText = split.get();
        Participant participant = participants.computeIfAbsent(
                senderAndText.sender(), name -> Participant.of(SOURCE, name, name));
        MessageType type = WhatsAppContentClassifier.classify(senderAndText.text());
        return new Draft(lineNumber, sentAt, participant.id(), type, senderAndText.text(), raw);
    }

    private WhatsAppDateOrder detectOrder(List<String> rawLines, ImportRequest request, List<ParseIssue> issues) {
        List<String> dates = new ArrayList<>();
        for (String raw : rawLines) {
            WhatsAppLineParser.readHeader(WhatsAppLineParser.normalize(raw))
                    .ifPresent(header -> dates.add(header.date()));
        }
        WhatsAppDateOrder detected = WhatsAppTimestamps.detectOrder(dates, request.defaultLocaleHint());
        WhatsAppDateOrder fallback = WhatsAppTimestamps.fromLocaleHint(request.defaultLocaleHint());
        boolean everyDateAmbiguous = !dates.isEmpty() && detected == fallback && !evidenceFound(dates);
        if (everyDateAmbiguous) {
            issues.add(ParseIssue.warning(
                    0,
                    "no date in this export distinguishes day from month; assumed " + detected,
                    null));
        }
        return detected;
    }

    /** True when at least one date has a component above 12, which settles the order on its own. */
    private boolean evidenceFound(List<String> dates) {
        for (String date : dates) {
            String[] parts = date.split("[./-]");
            if (parts.length != 3) {
                continue;
            }
            if (parts[0].length() == 4) {
                return true;
            }
            if (numeric(parts[0]) > 12 || numeric(parts[1]) > 12) {
                return true;
            }
        }
        return false;
    }

    private int numeric(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private List<String> readLines(Reader reader) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader buffered = new BufferedReader(reader)) {
            String line;
            while ((line = buffered.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** A message being assembled, still collecting its continuation lines. */
    private static final class Draft {
        private final int lineNumber;
        private final Instant sentAt;
        private final UUID senderId;
        private final StringBuilder text;
        private final StringBuilder raw;
        private MessageType type;

        private Draft(int lineNumber, Instant sentAt, UUID senderId, MessageType type, String text, String raw) {
            this.lineNumber = lineNumber;
            this.sentAt = sentAt;
            this.senderId = senderId;
            this.type = type;
            this.text = new StringBuilder(text == null ? "" : text);
            this.raw = new StringBuilder(raw);
        }

        private void append(String normalizedLine, String rawLine) {
            text.append('\n').append(normalizedLine);
            raw.append('\n').append(rawLine);
            // A placeholder that turned out to have more lines is ordinary text after all.
            if (type == MessageType.UNKNOWN) {
                type = MessageType.TEXT;
            }
        }

        private CanonicalMessage toMessage(UUID conversationId, ImportRequest request, Map<String, Integer> collisions) {
            String body = text.toString().stripTrailing();
            String sourceMessageId = deriveSourceMessageId(body, collisions);
            return new CanonicalMessage(
                    CanonicalIds.messageId(SOURCE, request.sourceConversationId(), sourceMessageId),
                    SOURCE,
                    sourceMessageId,
                    conversationId,
                    senderId,
                    sentAt,
                    type,
                    body,
                    null,
                    Map.of("lineNumber", Integer.toString(lineNumber)),
                    raw.toString());
        }

        /**
         * WhatsApp exports carry no message ids, so one is derived from what identifies a message:
         * when it was sent, who sent it and what it said. Re-importing the same export therefore
         * produces the same ids, and provenance recorded earlier keeps pointing at the right messages.
         */
        private String deriveSourceMessageId(String body, Map<String, Integer> collisions) {
            String identity = sentAt + "|" + senderId + "|" + body;
            String digest = sha256Hex(identity).substring(0, 16);
            int seen = collisions.merge(digest, 1, Integer::sum);
            return seen == 1 ? digest : digest + "-" + seen;
        }

        private static String sha256Hex(String value) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(hash.length * 2);
                for (byte b : hash) {
                    hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
            }
        }
    }
}
