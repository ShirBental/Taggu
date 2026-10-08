package com.taggu.ingest.whatsapp;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises the timestamp header that begins every WhatsApp message line.
 *
 * <p>A line either starts a message or continues the previous one. That single decision is what makes
 * multiline messages work, so it is kept here on its own and covered by its own tests.
 *
 * <p>One pattern covers the known export shapes rather than one pattern per locale:
 *
 * <pre>
 *   12/03/2023, 14:32 - Alice: Hello          (Android, 24h)
 *   3/12/23, 2:32 PM - Alice: Hello           (Android, 12h)
 *   [12/03/2023, 14:32:11] Alice: Hello       (iOS)
 *   [3/12/23, 2:32:11 PM] Alice: Hello        (iOS, 12h)
 *   2023-03-12, 14:32 - Alice: Hello          (ISO-style dates)
 *   12.03.2023, 14.32 - Alice: Hello          (locales that write the time with dots)
 * </pre>
 */
public final class WhatsAppLineParser {

    private static final Pattern HEADER = Pattern.compile(
            "^\\[?\\s*(?<date>\\d{1,4}[./-]\\d{1,2}[./-]\\d{2,4}),?\\s+"
                    + "(?<time>\\d{1,2}[:.]\\d{2}(?:[:.]\\d{2})?)\\s*"
                    + "(?<meridiem>[APap]\\.?[Mm]\\.?)?\\s*"
                    + "(?:\\]|[-–—])\\s*"
                    + "(?<body>.*)$");

    /** Direction and formatting marks, and the byte-order mark, that exports scatter through lines. */
    private static final Pattern MARKS = Pattern.compile("[\\u200E\\u200F\\u202A-\\u202E\\uFEFF]");

    private static final char LEFT_TO_RIGHT_MARK = '\u200E';

    /** The longest run of characters before ": " still plausible as a sender name. */
    private static final int MAX_SENDER_LENGTH = 60;

    private WhatsAppLineParser() {}

    /**
     * Strips the invisible marks WhatsApp sprinkles through exports and normalises exotic spaces.
     *
     * <p>iOS exports prefix lines with a left-to-right mark and separate the time from AM/PM with a
     * narrow no-break space; without this step those lines look malformed to every pattern below.
     * The original line is kept untouched elsewhere as {@code rawContent}.
     */
    public static String normalize(String line) {
        return MARKS.matcher(normalizeSpaces(line)).replaceAll("").stripTrailing();
    }

    private static String normalizeSpaces(String line) {
        return line.replace('\u00A0', ' ').replace('\u202F', ' ').replace('\u2009', ' ');
    }

    /**
     * True when the text of a message line is preceded by a left-to-right mark in the export.
     *
     * <p>iOS writes that mark in front of everything the platform produced rather than a person typed:
     * media placeholders, and notices such as "Dana added Ron", which iOS also attributes to a sender.
     * {@link #normalize} removes the mark, so this has to be asked of the untouched line.
     *
     * @param rawLine the line as it appears in the export
     * @param text the text {@link #splitSender} found on that line
     */
    public static boolean startsWithPlatformMark(String rawLine, String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String line = normalizeSpaces(rawLine);
        int end = line.length();
        while (end > 0 && (Character.isWhitespace(line.charAt(end - 1)) || isMark(line.charAt(end - 1)))) {
            end--;
        }
        // Walk back over the text's own characters to find where it starts in the marked line.
        int start = end;
        int remaining = text.stripTrailing().length();
        while (start > 0 && remaining > 0) {
            start--;
            if (!isMark(line.charAt(start))) {
                remaining--;
            }
        }
        while (start > 0 && isMark(line.charAt(start - 1))) {
            start--;
            if (line.charAt(start) == LEFT_TO_RIGHT_MARK) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMark(char c) {
        return c == LEFT_TO_RIGHT_MARK || c == '\u200F' || (c >= '\u202A' && c <= '\u202E') || c == '\uFEFF';
    }

    /** Reads the timestamp header of a line, or returns empty when the line is a continuation. */
    public static Optional<WhatsAppHeader> readHeader(String normalizedLine) {
        Matcher matcher = HEADER.matcher(normalizedLine);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new WhatsAppHeader(
                matcher.group("date"), matcher.group("time"), matcher.group("meridiem"), matcher.group("body")));
    }

    /**
     * Splits {@code "Alice: Hello"} into sender and text.
     *
     * <p>Returns empty when the body has no sender, which is how platform notices such as "Messages
     * and calls are end-to-end encrypted." are told apart from what people wrote. A long prefix is
     * rejected so that a notice containing a colon ("Alice changed the subject to Trip: 2024") is not
     * mistaken for a very talkative sender.
     */
    public static Optional<SenderAndText> splitSender(String body) {
        int colon = body.indexOf(": ");
        int end = colon + 2;
        if (colon < 0 && body.endsWith(":")) {
            colon = body.length() - 1;
            end = body.length();
        }
        if (colon <= 0 || colon > MAX_SENDER_LENGTH) {
            return Optional.empty();
        }
        String sender = body.substring(0, colon).trim();
        // WhatsApp writes "~ Name" for someone who is not in the exporter's contacts. The tilde is the
        // app's annotation, not part of the name, and keeping it would make "~ Noa" and "Noa" two people.
        if (sender.startsWith("~")) {
            sender = sender.substring(1).trim();
        }
        if (sender.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SenderAndText(sender, body.substring(end)));
    }

    /** The timestamp header of a message line, still as written in the file. */
    public record WhatsAppHeader(String date, String time, String meridiem, String body) {}

    /** A message body split into who wrote it and what they wrote. */
    public record SenderAndText(String sender, String text) {}
}
