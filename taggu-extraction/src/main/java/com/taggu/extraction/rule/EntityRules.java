package com.taggu.extraction.rule;

import com.taggu.core.knowledge.EntityType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative surface patterns for the entities a chat mentions.
 *
 * <p>These rules are meant to be obviously right rather than complete: a legal suffix really does mark
 * an organisation, and "at Ramat Gan" really is a place. Anything needing world knowledge is left for
 * the model-backed extractor to come, which is why precision is preferred to recall here.
 */
public final class EntityRules {

    private static final Pattern ORGANIZATION = Pattern.compile(
            "\\b([A-Z][\\p{L}&.'-]*(?:\\s+[A-Z][\\p{L}&.'-]*){0,3})\\s+(Inc|LLC|Ltd|GmbH|Corp|Co)\\b\\.?");

    private static final Pattern LOCATION =
            Pattern.compile("\\bat\\s+([A-Z][\\p{L}'-]+(?:\\s+[A-Z][\\p{L}'-]+){0,2})");

    private static final Pattern PRODUCT = Pattern.compile(
            "(?i)\\b(?:bought|ordered|purchased)\\s+(?:a|an|the|some)?\\s*([\\p{L}][\\p{L}\\s'-]{2,40})");

    private static final Set<String> NOT_A_PLACE = Set.of(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "home", "work", "school");

    private EntityRules() {}

    /** Organisations named with a legal suffix. */
    public static List<Found> organizations(String text) {
        List<Found> found = new ArrayList<>();
        Matcher matcher = ORGANIZATION.matcher(text);
        while (matcher.find()) {
            found.add(new Found(EntityType.ORGANIZATION, (matcher.group(1) + " " + matcher.group(2)).trim()));
        }
        return found;
    }

    /** Capitalised places introduced by "at". */
    public static List<Found> locations(String text, Set<String> knownPeople) {
        List<Found> found = new ArrayList<>();
        Matcher matcher = LOCATION.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            String normalized = candidate.toLowerCase(Locale.ROOT);
            if (NOT_A_PLACE.contains(normalized) || knownPeople.contains(normalized)) {
                continue;
            }
            found.add(new Found(EntityType.LOCATION, candidate));
        }
        return found;
    }

    /** Things the conversation says someone bought or ordered. */
    public static List<Found> products(String text) {
        List<Found> found = new ArrayList<>();
        Matcher matcher = PRODUCT.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).split("(?i)\\b(?:for|from|at|on|in|and)\\b")[0].trim();
            if (candidate.length() >= 3) {
                found.add(new Found(EntityType.PRODUCT, candidate));
            }
        }
        return found;
    }

    /** An entity spotted in a piece of text, before provenance is attached. */
    public record Found(EntityType type, String name) {}
}
