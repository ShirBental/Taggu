package com.taggu.extraction.rule;

import com.taggu.core.extraction.ExtractionContext;
import com.taggu.core.extraction.KnowledgeExtractor;
import com.taggu.core.knowledge.ActionItem;
import com.taggu.core.knowledge.ActionItemStatus;
import com.taggu.core.knowledge.Decision;
import com.taggu.core.knowledge.EntityType;
import com.taggu.core.knowledge.Event;
import com.taggu.core.knowledge.ExtractedEntity;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Fact;
import com.taggu.core.knowledge.Provenance;
import com.taggu.core.knowledge.Tag;
import com.taggu.core.knowledge.TimePrecision;
import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.MessageType;
import com.taggu.core.model.Participant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small deterministic extractor that proves the pipeline end to end.
 *
 * <p>It exists to show that a window of canonical messages can become tags, entities, facts, events,
 * action items and decisions with provenance attached to every one of them, using nothing but regular
 * expressions. It is not trying to understand language: a model-backed {@link KnowledgeExtractor} will
 * replace it, and because the interface and the result shape are the same, nothing downstream changes
 * when it does.
 *
 * <p>Two properties matter more than its accuracy:
 *
 * <ul>
 *   <li>Every item it returns cites at least one message in the window's focus, so overlapping windows
 *       cannot produce the same item twice.
 *   <li>It reads across messages. "Want to come Sunday?" and a later "Around 4?" become one event
 *       citing both, which is the behaviour the whole windowing design exists for.
 * </ul>
 */
public final class RuleBasedKnowledgeExtractor implements KnowledgeExtractor {

    public static final String NAME = "rule-based-v1";

    private static final double TAG_CONFIDENCE = 0.6;
    private static final double ENTITY_CONFIDENCE = 0.5;
    private static final double FACT_CONFIDENCE = 0.55;
    private static final double EVENT_CONFIDENCE = 0.5;
    private static final double ACTION_CONFIDENCE = 0.5;
    private static final double DECISION_CONFIDENCE = 0.5;

    private static final Pattern MONEY = Pattern.compile(
            "(?i)(?:[$€£₪]\\s?\\d[\\d,.]*|\\b\\d[\\d,.]*\\s?(?:usd|eur|gbp|ils|dollars?|euros?|shekels?)\\b)");

    private static final Pattern PRICE_TALK =
            Pattern.compile("(?i)\\b(quoted|quote|costs?|charged?|price|priced|invoice|total)\\b");

    private static final Pattern SELF_COMMITMENT =
            Pattern.compile("(?i)\\bi(?:'|’)?(?:ll|\\s+will|\\s+am going to)\\s+(?<task>.{3,160})");

    private static final Pattern REQUEST =
            Pattern.compile("(?i)\\b(?:can|could|would)\\s+you\\s+(?:please\\s+)?(?<task>.{3,160})");

    private static final Pattern REMINDER = Pattern.compile(
            "(?i)\\b(?:don(?:'|’)?t forget to|remember to|need to|needs to|have to|make sure to)\\s+(?<task>.{3,160})");

    private static final Pattern DECIDED = Pattern.compile(
            "(?i)\\b(?:let(?:'|’)?s|we(?:'|’)?ll|we will|we decided|decided to|going with|we agreed|agreed to)\\b");

    private static final Pattern EVENT_HINT = Pattern.compile(
            "(?i)\\b(come|coming|meet|meeting|visit|dinner|lunch|breakfast|party|appointment|pick up|drop off|"
                    + "see you|arrive|arriving|flight|call|game|show|book|booked)\\b");

    private static final Pattern AFFIRMATION = Pattern.compile(
            "(?i)^\\W*(sure|ok|okay|yes|yep|yeah|perfect|great|sounds good|works for me|deal|👍)\\W*$");

    /** How far ahead of a day mention a time may appear and still belong to the same plan. */
    private static final int TIME_LOOKAHEAD_MESSAGES = 6;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ExtractionResult extract(ExtractionContext context) {
        ExtractionResult.Builder result = ExtractionResult.builder(NAME);
        Set<String> knownPeople = peopleNames(context);

        Map<String, List<UUID>> tagHits = new LinkedHashMap<>();
        Map<EntityKey, List<UUID>> entityHits = new LinkedHashMap<>();
        Set<UUID> messagesWithActions = new LinkedHashSet<>();

        for (CanonicalMessage message : context.focus()) {
            if (!isReadable(message)) {
                continue;
            }
            collectTags(message, tagHits);
            collectEntities(message, knownPeople, entityHits);
            collectFacts(message, result);
            if (collectActions(message, context, result)) {
                messagesWithActions.add(message.id());
            }
            collectDecisions(message, result);
        }

        tagHits.forEach((slug, messageIds) ->
                result.tag(Tag.of(slug, TAG_CONFIDENCE, Provenance.ofMessages(messageIds))));
        entityHits.forEach((key, messageIds) -> result.entity(
                ExtractedEntity.of(key.type(), key.name(), ENTITY_CONFIDENCE, Provenance.ofMessages(messageIds))));

        collectEvents(context, messagesWithActions, result);
        return result.build();
    }

    // --- tags and entities -------------------------------------------------------------------

    private void collectTags(CanonicalMessage message, Map<String, List<UUID>> hits) {
        for (String slug : TagRules.tagsFor(message.text())) {
            hits.computeIfAbsent(slug, key -> new ArrayList<>()).add(message.id());
        }
    }

    private void collectEntities(CanonicalMessage message, Set<String> knownPeople, Map<EntityKey, List<UUID>> hits) {
        String text = message.text();
        List<EntityRules.Found> found = new ArrayList<>();
        found.addAll(EntityRules.organizations(text));
        found.addAll(EntityRules.locations(text, knownPeople));
        found.addAll(EntityRules.products(text));
        for (String person : knownPeople) {
            if (containsWord(text.toLowerCase(Locale.ROOT), person)) {
                found.add(new EntityRules.Found(EntityType.PERSON, person));
            }
        }
        for (EntityRules.Found entity : found) {
            EntityKey key = new EntityKey(entity.type(), ExtractedEntity.normalize(entity.name()), entity.name());
            hits.computeIfAbsent(key, ignored -> new ArrayList<>()).add(message.id());
        }
    }

    // --- facts -------------------------------------------------------------------------------

    private void collectFacts(CanonicalMessage message, ExtractionResult.Builder result) {
        for (String sentence : Sentences.split(message.text())) {
            boolean hasMoney = MONEY.matcher(sentence).find();
            if (!hasMoney && !(PRICE_TALK.matcher(sentence).find() && sentence.matches(".*\\d.*"))) {
                continue;
            }
            Matcher money = MONEY.matcher(sentence);
            String subject = money.find() ? money.group().trim() : null;
            result.fact(new Fact(sentence, subject, FACT_CONFIDENCE, Provenance.ofMessages(message.id())));
        }
    }

    // --- action items ------------------------------------------------------------------------

    /** @return true when the message produced at least one action item */
    private boolean collectActions(
            CanonicalMessage message, ExtractionContext context, ExtractionResult.Builder result) {
        boolean found = false;
        for (String sentence : Sentences.split(message.text())) {
            found |= addAction(
                    SELF_COMMITMENT, sentence, message, context.displayNameOf(message.senderId()), context, result);
            found |= addAction(REQUEST, sentence, message, otherParticipant(context, message.senderId()), context, result);
            found |= addAction(REMINDER, sentence, message, null, context, result);
        }
        return found;
    }

    private boolean addAction(
            Pattern pattern,
            String sentence,
            CanonicalMessage message,
            String assignee,
            ExtractionContext context,
            ExtractionResult.Builder result) {
        Matcher matcher = pattern.matcher(sentence);
        if (!matcher.find()) {
            return false;
        }
        String task = trimToClause(matcher.group("task"));
        if (task.isBlank()) {
            return false;
        }
        Optional<ResolvedTime> due = resolveTime(sentence, message, context);
        result.action(new ActionItem(
                task,
                assignee,
                due.map(ResolvedTime::instantOrNull).orElse(null),
                due.map(ResolvedTime::precision).orElse(TimePrecision.UNRESOLVED),
                due.map(ResolvedTime::expression).orElse(null),
                ActionItemStatus.OPEN,
                ACTION_CONFIDENCE,
                Provenance.ofMessages(message.id())));
        return true;
    }

    // --- decisions ---------------------------------------------------------------------------

    private void collectDecisions(CanonicalMessage message, ExtractionResult.Builder result) {
        for (String sentence : Sentences.split(message.text())) {
            if (DECIDED.matcher(sentence).find()) {
                result.decision(new Decision(
                        sentence, message.sentAt(), DECISION_CONFIDENCE, Provenance.ofMessages(message.id())));
            }
        }
    }

    // --- events ------------------------------------------------------------------------------

    /**
     * Builds events from a day mention plus, where the conversation supplies one, a time given in a
     * later message. This is the one rule that deliberately reads across messages.
     */
    private void collectEvents(
            ExtractionContext context, Set<UUID> messagesWithActions, ExtractionResult.Builder result) {
        List<CanonicalMessage> all = context.allMessages();
        for (int index = 0; index < all.size(); index++) {
            CanonicalMessage message = all.get(index);
            if (!isReadable(message) || !context.isInFocus(message.id())) {
                continue;
            }
            // "I'll call them tomorrow" is already an action item with a deadline. Recording it as an
            // event as well would say the same thing twice.
            if (messagesWithActions.contains(message.id())) {
                continue;
            }
            Optional<String> dayPhrase = TimeExpressions.findDayPhrase(message.text());
            if (dayPhrase.isEmpty()) {
                continue;
            }
            LocalDate referenceDate = message.sentAt().atZone(context.zone()).toLocalDate();
            Optional<LocalDate> day = TimeExpressions.resolveDay(dayPhrase.get(), referenceDate);
            if (day.isEmpty()) {
                continue;
            }

            List<UUID> evidence = new ArrayList<>();
            evidence.add(message.id());
            Optional<TimeExpressions.ClockTime> clock = TimeExpressions.findClockTime(message.text());
            String timeExpression = dayPhrase.get();

            if (clock.isEmpty()) {
                int limit = Math.min(all.size(), index + 1 + TIME_LOOKAHEAD_MESSAGES);
                for (int ahead = index + 1; ahead < limit && clock.isEmpty(); ahead++) {
                    CanonicalMessage later = all.get(ahead);
                    if (!isReadable(later)) {
                        continue;
                    }
                    Optional<TimeExpressions.ClockTime> found = TimeExpressions.findClockTime(later.text());
                    if (found.isPresent()) {
                        clock = found;
                        evidence.add(later.id());
                        if (ahead + 1 < all.size() && AFFIRMATION.matcher(all.get(ahead + 1).text()).matches()) {
                            evidence.add(all.get(ahead + 1).id());
                        }
                    }
                }
            }

            boolean worthRecording = clock.isPresent() || EVENT_HINT.matcher(message.text()).find();
            if (!worthRecording) {
                continue;
            }

            TimePrecision precision = clock.map(TimeExpressions.ClockTime::precision).orElse(TimePrecision.APPROXIMATE);
            if (clock.isPresent()) {
                timeExpression = dayPhrase.get() + " " + clock.get().expression();
            }
            var startsAt = clock.map(found -> day.get().atTime(found.time()))
                    .orElseGet(() -> day.get().atStartOfDay())
                    .atZone(context.zone())
                    .toInstant();

            result.event(new Event(
                    Sentences.summarize(message.text(), 120),
                    startsAt,
                    null,
                    precision,
                    timeExpression,
                    null,
                    EVENT_CONFIDENCE,
                    Provenance.ofMessages(evidence)));
        }
    }

    // --- shared helpers ----------------------------------------------------------------------

    private Optional<ResolvedTime> resolveTime(String sentence, CanonicalMessage message, ExtractionContext context) {
        Optional<String> dayPhrase = TimeExpressions.findDayPhrase(sentence);
        if (dayPhrase.isEmpty()) {
            return Optional.empty();
        }
        LocalDate referenceDate = message.sentAt().atZone(context.zone()).toLocalDate();
        Optional<LocalDate> day = TimeExpressions.resolveDay(dayPhrase.get(), referenceDate);
        if (day.isEmpty()) {
            return Optional.empty();
        }
        Optional<TimeExpressions.ClockTime> clock = TimeExpressions.findClockTime(sentence);
        var dateTime = clock.map(found -> day.get().atTime(found.time())).orElseGet(() -> day.get().atStartOfDay());
        TimePrecision precision = clock.map(TimeExpressions.ClockTime::precision).orElse(TimePrecision.APPROXIMATE);
        String expression = clock.map(found -> dayPhrase.get() + " " + found.expression()).orElse(dayPhrase.get());
        return Optional.of(new ResolvedTime(dateTime.atZone(context.zone()).toInstant(), precision, expression));
    }

    private Set<String> peopleNames(ExtractionContext context) {
        Set<String> names = new LinkedHashSet<>();
        for (Participant participant : context.conversation().participants()) {
            names.add(participant.displayName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private String otherParticipant(ExtractionContext context, UUID senderId) {
        List<Participant> participants = context.conversation().participants();
        if (participants.size() != 2) {
            return null;
        }
        return participants.stream()
                .filter(participant -> !participant.id().equals(senderId))
                .map(Participant::displayName)
                .findFirst()
                .orElse(null);
    }

    private static boolean isReadable(CanonicalMessage message) {
        return message.messageType() == MessageType.TEXT && message.hasText();
    }

    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(haystack).find();
    }

    /** Cuts a captured task at the end of its clause so trailing chatter is not part of the item. */
    private static String trimToClause(String task) {
        String[] parts = task.split("[.?!;\\n]");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    private record EntityKey(EntityType type, String normalized, String name) {
        @Override
        public boolean equals(Object other) {
            return other instanceof EntityKey key && key.type == type && key.normalized.equals(normalized);
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + normalized.hashCode();
        }
    }

    private record ResolvedTime(java.time.Instant instant, TimePrecision precision, String expression) {
        java.time.Instant instantOrNull() {
            return instant;
        }
    }
}
