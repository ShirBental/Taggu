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
 * </pre>
 */
public final class WhatsAppLineParser {

    private static final Pattern HEADER = Pattern.compile(
            "^\\[?\\s*(?<date>\\d{1,4}[./-]\\d{1,2}[./-]\\d{2,4}),?\\s+"
                    + "(?<time>\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*"
                    + "(?<meridiem>[APap]\\.?[Mm]\\.?)?\\s*"
                    + "(?:\\]|[-–—])\\s*"
                    + "(?<body>.*)$");

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
        return line.replaceAll("[‎‏‪-‮﻿]", "")
                .replace(' ', ' ')
                .replace(' ', ' ')
                .replace(' ', ' ')
                .stripTrailing();
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
