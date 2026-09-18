package com.taggu.ingest.whatsapp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads WhatsApp's wall-clock timestamps.
 *
 * <p>Exports carry no timezone and no format declaration, so two things have to be worked out rather
 * than assumed. The zone comes from the caller. The day/month order is inferred from the file itself:
 * a component above 12 can only be a day, so one such date anywhere in the export settles the order
 * for the whole file. Only when a file gives no evidence at all does a hint or a default decide.
 */
public final class WhatsAppTimestamps {

    private WhatsAppTimestamps() {}

    /**
     * Works out the day/month order of an export from the dates it contains.
     *
     * @param dates every date string found in the file, as written
     * @param localeHint an optional locale such as {@code "en-US"}, consulted only when the file
     *     itself is ambiguous
     */
    public static WhatsAppDateOrder detectOrder(List<String> dates, String localeHint) {
        boolean sawYearFirst = false;
        for (String date : dates) {
            String[] parts = split(date);
            if (parts == null) {
                continue;
            }
            if (parts[0].length() == 4) {
                sawYearFirst = true;
                continue;
            }
            int first = parseIntOrZero(parts[0]);
            int second = parseIntOrZero(parts[1]);
            if (first > 12 && second <= 12) {
                return WhatsAppDateOrder.DAY_FIRST;
            }
            if (second > 12 && first <= 12) {
                return WhatsAppDateOrder.MONTH_FIRST;
            }
        }
        if (sawYearFirst) {
            return WhatsAppDateOrder.YEAR_FIRST;
        }
        return fromLocaleHint(localeHint);
    }

    /**
     * The order implied by a locale hint. WhatsApp writes month-first only for a handful of locales,
     * so day-first is the fallback when nothing is known.
     */
    public static WhatsAppDateOrder fromLocaleHint(String localeHint) {
        if (localeHint == null || localeHint.isBlank()) {
            return WhatsAppDateOrder.DAY_FIRST;
        }
        String hint = localeHint.trim().toUpperCase(Locale.ROOT);
        boolean monthFirst = hint.equals("EN-US")
                || hint.equals("EN_US")
                || hint.endsWith("-US")
                || hint.endsWith("_US")
                || hint.equals("US");
        return monthFirst ? WhatsAppDateOrder.MONTH_FIRST : WhatsAppDateOrder.DAY_FIRST;
    }

    /** Parses a header's date and time into local wall-clock time, or empty when either is unusable. */
    public static Optional<LocalDateTime> parse(
            String date, String time, String meridiem, WhatsAppDateOrder order) {
        Optional<LocalDate> localDate = parseDate(date, order);
        Optional<LocalTime> localTime = parseTime(time, meridiem);
        if (localDate.isEmpty() || localTime.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(LocalDateTime.of(localDate.get(), localTime.get()));
    }

    static Optional<LocalDate> parseDate(String date, WhatsAppDateOrder order) {
        String[] parts = split(date);
        if (parts == null) {
            return Optional.empty();
        }
        int year;
        int month;
        int day;
        if (parts[0].length() == 4 || order == WhatsAppDateOrder.YEAR_FIRST) {
            year = parseIntOrZero(parts[0]);
            month = parseIntOrZero(parts[1]);
            day = parseIntOrZero(parts[2]);
        } else {
            year = expandYear(parseIntOrZero(parts[2]));
            if (order == WhatsAppDateOrder.MONTH_FIRST) {
                month = parseIntOrZero(parts[0]);
                day = parseIntOrZero(parts[1]);
            } else {
                day = parseIntOrZero(parts[0]);
                month = parseIntOrZero(parts[1]);
            }
        }
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    static Optional<LocalTime> parseTime(String time, String meridiem) {
        String[] parts = time.split(":");
        if (parts.length < 2 || parts.length > 3) {
            return Optional.empty();
        }
        int hour = parseIntOrZero(parts[0]);
        int minute = parseIntOrZero(parts[1]);
        int second = parts.length == 3 ? parseIntOrZero(parts[2]) : 0;
        if (meridiem != null && !meridiem.isBlank()) {
            boolean pm = meridiem.replace(".", "").toUpperCase(Locale.ROOT).startsWith("P");
            if (hour == 12) {
                hour = pm ? 12 : 0;
            } else if (pm) {
                hour += 12;
            }
        }
        try {
            return Optional.of(LocalTime.of(hour, minute, second));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /** WhatsApp predates no export, so a two-digit year is always in this century. */
    private static int expandYear(int year) {
        return year < 100 ? 2000 + year : year;
    }

    private static String[] split(String date) {
        String[] parts = date.split("[./-]");
        return parts.length == 3 ? parts : null;
    }

    private static int parseIntOrZero(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
