package com.taggu.extraction.rule;

import com.taggu.core.knowledge.TimePrecision;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.temporal.TemporalAdjusters;

/**
 * Finds and resolves the everyday ways people put times into a chat.
 *
 * <p>Conversations do not carry dates, they carry "Sunday", "tomorrow" and "around 4". Resolving those
 * needs a reference date, which is the date of the message the phrase was written in.
 */
public final class TimeExpressions {

    private static final Pattern DAY = Pattern.compile(
            "(?i)\\b(today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b");

    private static final Pattern CLOCK = Pattern.compile(
            "(?i)(?<approx>\\b(?:around|about|circa|approximately|~)\\s+)?"
                    + "\\b(?<hour>\\d{1,2})(?::(?<minute>\\d{2}))?\\s*(?<meridiem>am|pm|a\\.m\\.|p\\.m\\.)?\\b");

    /** A bare hour at or below this is read as an afternoon time, the way people use it in chat. */
    private static final int BARE_HOUR_PM_THRESHOLD = 7;

    private TimeExpressions() {}

    /** The day phrase in a message, as written. */
    public static Optional<String> findDayPhrase(String text) {
        Matcher matcher = DAY.matcher(text);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    /** Resolves a day phrase against the date the message was written on. */
    public static Optional<LocalDate> resolveDay(String phrase, LocalDate referenceDate) {
        String normalized = phrase.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "today", "tonight" -> Optional.of(referenceDate);
            case "tomorrow" -> Optional.of(referenceDate.plusDays(1));
            default -> weekday(normalized).map(day -> referenceDate.with(TemporalAdjusters.nextOrSame(day)));
        };
    }

    /**
     * The clock time in a message, if it has one.
     *
     * <p>Returns empty for text where a number is clearly not a time, which keeps prices and
     * quantities out of the event pipeline.
     */
    public static Optional<ClockTime> findClockTime(String text) {
        Matcher matcher = CLOCK.matcher(text);
        while (matcher.find()) {
            String hourGroup = matcher.group("hour");
            int hour = Integer.parseInt(hourGroup);
            String meridiem = matcher.group("meridiem");
            boolean hasMinutes = matcher.group("minute") != null;
            boolean approximate = matcher.group("approx") != null;

            boolean looksLikeTime = meridiem != null || hasMinutes || approximate;
            if (!looksLikeTime || hour > 23) {
                continue;
            }
            if (isPartOfLargerNumber(text, matcher.start("hour"), matcher.end("hour"))) {
                continue;
            }

            int minute = hasMinutes ? Integer.parseInt(matcher.group("minute")) : 0;
            if (minute > 59) {
                continue;
            }
            TimePrecision precision = TimePrecision.EXACT;
            if (meridiem != null) {
                boolean pm = meridiem.toLowerCase(Locale.ROOT).startsWith("p");
                if (hour == 12) {
                    hour = pm ? 12 : 0;
                } else if (pm) {
                    hour += 12;
                }
            } else if (hour <= BARE_HOUR_PM_THRESHOLD) {
                // "around 4" in a chat is the afternoon; the guess is recorded as approximate.
                hour += 12;
                precision = TimePrecision.APPROXIMATE;
            }
            if (approximate) {
                precision = TimePrecision.APPROXIMATE;
            }
            return Optional.of(new ClockTime(LocalTime.of(hour, minute), precision, matcher.group().trim()));
        }
        return Optional.empty();
    }

    private static boolean isPartOfLargerNumber(String text, int start, int end) {
        boolean digitBefore = start > 0 && Character.isDigit(text.charAt(start - 1));
        boolean currencyBefore = start > 0 && "$€£₪".indexOf(text.charAt(start - 1)) >= 0;
        boolean digitAfter = end < text.length() && Character.isDigit(text.charAt(end));
        return digitBefore || digitAfter || currencyBefore;
    }

    private static Optional<DayOfWeek> weekday(String value) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.name().equalsIgnoreCase(value)) {
                return Optional.of(day);
            }
        }
        return Optional.empty();
    }

    /** A clock time found in a message, with how firmly it was stated. */
    public record ClockTime(LocalTime time, TimePrecision precision, String expression) {}
}
