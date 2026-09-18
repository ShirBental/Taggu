package com.taggu.extraction.rule;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The keyword vocabulary behind {@link RuleBasedKnowledgeExtractor}'s tags.
 *
 * <p>Tags are the cheapest useful signal in the system: they narrow a search before anything has to
 * understand a sentence. The list is deliberately short and obvious, since its job is to prove the
 * pipeline rather than to be exhaustive. A model-backed extractor will replace the vocabulary without
 * changing anything that reads tags.
 */
public final class TagRules {

    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("school", List.of("school", "teacher", "homework", "classroom", "parent-teacher", "kindergarten"));
        KEYWORDS.put("birthday", List.of("birthday", "bday", "birth day", "cake", "party"));
        KEYWORDS.put("house", List.of("roof", "plumber", "contractor", "renovation", "landlord", "mortgage", "repair"));
        KEYWORDS.put("job-search", List.of("interview", "resume", "cv", "recruiter", "job offer", "hiring"));
        KEYWORDS.put("travel", List.of("flight", "airport", "hotel", "passport", "boarding", "trip", "itinerary"));
        KEYWORDS.put("purchase", List.of("quote", "quoted", "invoice", "receipt", "order", "bought", "price", "paid"));
        KEYWORDS.put("health", List.of("doctor", "dentist", "appointment", "prescription", "clinic", "pharmacy"));
        KEYWORDS.put("food", List.of("dinner", "lunch", "breakfast", "restaurant", "recipe", "groceries"));
    }

    private static final Map<String, Pattern> PATTERNS = compile();

    private TagRules() {}

    /** Every tag slug whose vocabulary appears in the text. */
    public static Set<String> tagsFor(String text) {
        Set<String> matched = new LinkedHashSet<>();
        String haystack = text.toLowerCase(Locale.ROOT);
        PATTERNS.forEach((slug, pattern) -> {
            if (pattern.matcher(haystack).find()) {
                matched.add(slug);
            }
        });
        return matched;
    }

    /** The slugs this rule set can produce, for documentation and tests. */
    public static Set<String> knownSlugs() {
        return KEYWORDS.keySet();
    }

    private static Map<String, Pattern> compile() {
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        KEYWORDS.forEach((slug, words) -> {
            String alternatives = String.join("|", words.stream().map(Pattern::quote).toList());
            compiled.put(slug, Pattern.compile("\\b(?:" + alternatives + ")\\b"));
        });
        return Map.copyOf(compiled);
    }
}
