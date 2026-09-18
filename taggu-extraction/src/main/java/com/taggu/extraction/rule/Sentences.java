package com.taggu.extraction.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits message text into sentences.
 *
 * <p>Extracted knowledge reads better as a sentence than as a whole message, and a sentence is also
 * the narrowest evidence span worth recording. Chat punctuation is unreliable, so newlines count as
 * boundaries too.
 */
public final class Sentences {

    private Sentences() {}

    public static List<String> split(String text) {
        List<String> sentences = new ArrayList<>();
        for (String line : text.split("\\R")) {
            for (String part : line.split("(?<=[.!?])\\s+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    sentences.add(trimmed);
                }
            }
        }
        return sentences;
    }

    /** Shortens a sentence for use as a title, without cutting a word in half. */
    public static String summarize(String text, int maxLength) {
        String single = text.replaceAll("\\R", " ").replaceAll("\\s+", " ").trim();
        if (single.length() <= maxLength) {
            return single;
        }
        int cut = single.lastIndexOf(' ', maxLength);
        return single.substring(0, cut < 20 ? maxLength : cut).trim() + "...";
    }
}
