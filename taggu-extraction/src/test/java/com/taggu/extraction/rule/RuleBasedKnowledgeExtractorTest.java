package com.taggu.extraction.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.taggu.core.knowledge.ActionItem;
import com.taggu.core.knowledge.Decision;
import com.taggu.core.knowledge.Event;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Fact;
import com.taggu.core.knowledge.Tag;
import com.taggu.core.knowledge.TimePrecision;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RuleBasedKnowledgeExtractorTest {

    private final RuleBasedKnowledgeExtractor extractor = new RuleBasedKnowledgeExtractor();

    @Test
    @DisplayName("four messages that only make sense together become one event")
    void buildsOneEventFromSeveralMessages() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Mom", "Want to come Sunday?")
                .say("Yael", "Sure")
                .say("Mom", "Around 4?")
                .say("Yael", "Perfect");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.events()).singleElement().satisfies(event -> {
            assertThat(event.startsAt())
                    .isEqualTo(LocalDateTime.of(2024, 3, 17, 16, 0)
                            .atZone(ConversationFixture.ZONE)
                            .toInstant());
            assertThat(event.precision()).isEqualTo(TimePrecision.APPROXIMATE);
            assertThat(event.timeExpression()).contains("Sunday").contains("Around 4");
            // The evidence is all three messages the plan was actually made in.
            assertThat(event.provenance().messageIds())
                    .containsExactly(
                            fixture.message(0).id(), fixture.message(2).id(), fixture.message(3).id());
        });
    }

    @Test
    @DisplayName("a money amount becomes a fact citing the message it was stated in")
    void extractsFactsWithEvidence() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Dana", "The contractor came by about the roof.")
                .say("Dana", "He quoted $1,550 for the repair, including materials");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.facts()).singleElement().satisfies(fact -> {
            assertThat(fact.statement()).contains("$1,550");
            assertThat(fact.subject()).isEqualTo("$1,550");
            assertThat(fact.provenance().messageIds()).containsExactly(fixture.message(1).id());
        });
    }

    @Test
    @DisplayName("a promise becomes an action item, with its assignee and its deadline resolved")
    void extractsActionItems() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Ron", "I'll call him tomorrow to book it")
                .say("Dana", "Can you also ask about the gutter");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.actions()).hasSize(2);
        ActionItem promise = result.actions().get(0);
        assertThat(promise.description()).isEqualTo("call him tomorrow to book it");
        assertThat(promise.assignee()).isEqualTo("Ron");
        assertThat(promise.dueAt())
                .isEqualTo(LocalDateTime.of(2024, 3, 15, 0, 0)
                        .atZone(ConversationFixture.ZONE)
                        .toInstant());

        ActionItem request = result.actions().get(1);
        assertThat(request.description()).isEqualTo("also ask about the gutter");
        assertThat(request.assignee()).isEqualTo("Ron");
    }

    @Test
    @DisplayName("an explicit choice becomes a decision")
    void extractsDecisions() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Dana", "Nir Levy Ltd did the neighbours' place.")
                .say("Ron", "Let's go with them");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.decisions()).extracting(Decision::statement).containsExactly("Let's go with them");
        assertThat(result.decisions().get(0).provenance().messageIds())
                .containsExactly(fixture.message(1).id());
    }

    @Test
    @DisplayName("messages are classified into broad tags")
    void extractsTags() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Dana", "The contractor quoted a price for the roof repair")
                .say("Avi", "Parent-teacher evening is Thursday, the teacher wants to discuss homework");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.tags()).extracting(Tag::slug).contains("house", "purchase", "school");
        assertThat(result.tags()).allSatisfy(tag -> assertThat(tag.provenance().messageIds()).isNotEmpty());
    }

    @Test
    @DisplayName("organisations, places, products and people are picked out as entities")
    void extractsEntities() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Dana", "Nir Levy Ltd are doing it")
                .say("Ron", "I bought the airport transfer already")
                .say("Ron", "We are meeting at Ramat Gan")
                .say("Dana", "Ron will be late");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.entities()).extracting(entity -> entity.type() + ":" + entity.name())
                .contains("ORGANIZATION:Nir Levy Ltd", "LOCATION:Ramat Gan", "PERSON:ron");
        assertThat(result.entities()).anyMatch(entity -> entity.name().contains("airport transfer"));
    }

    @Test
    @DisplayName("everything extracted cites evidence, and only messages from its own window")
    void neverProducesKnowledgeWithoutProvenance() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Dana", "The contractor quoted $1,550 for the roof")
                .say("Ron", "Let's go with them")
                .say("Ron", "I'll call him tomorrow")
                .say("Mom", "Want to come Sunday? Around 4?");

        ExtractionResult result = extractor.extract(fixture.singleWindow());
        List<UUID> known = fixture.messages().stream().map(message -> message.id()).toList();

        assertThat(result.size()).isGreaterThan(0);
        List.of(
                        result.facts().stream().map(Fact::provenance).toList(),
                        result.events().stream().map(Event::provenance).toList(),
                        result.actions().stream().map(ActionItem::provenance).toList(),
                        result.decisions().stream().map(Decision::provenance).toList())
                .forEach(provenances -> assertThat(provenances).allSatisfy(provenance -> {
                    assertThat(provenance.messageIds()).isNotEmpty();
                    assertThat(provenance.messageIds()).isSubsetOf(known);
                }));
    }

    @Test
    @DisplayName("overlapping windows do not extract the same event twice")
    void doesNotDuplicateAcrossWindows() {
        ConversationFixture fixture = new ConversationFixture()
                .say("Mom", "Want to come Sunday?")
                .say("Yael", "Sure")
                .say("Mom", "Around 4?")
                .say("Yael", "Perfect")
                .say("Mom", "I'll make soup");

        List<ExtractionResult> perWindow =
                fixture.windows(2, 2).stream().map(extractor::extract).toList();
        ExtractionResult merged = ExtractionResult.merge(extractor.name(), perWindow);

        assertThat(merged.events()).hasSize(1);
    }

    @Test
    @DisplayName("a dated commitment is an action item, not also an event")
    void doesNotRecordACommitmentTwice() {
        ConversationFixture fixture = new ConversationFixture().say("Yael", "I'll call them tomorrow");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.actions()).singleElement().satisfies(action -> {
            assertThat(action.description()).isEqualTo("call them tomorrow");
            assertThat(action.dueAt()).isNotNull();
        });
        assertThat(result.events()).isEmpty();
    }

    @Test
    @DisplayName("an ordinary exchange produces nothing rather than noise")
    void staysQuietOnSmallTalk() {
        ConversationFixture fixture =
                new ConversationFixture().say("Dana", "Morning!").say("Ron", "Morning. Coffee?");

        ExtractionResult result = extractor.extract(fixture.singleWindow());

        assertThat(result.facts()).isEmpty();
        assertThat(result.events()).isEmpty();
        assertThat(result.actions()).isEmpty();
        assertThat(result.decisions()).isEmpty();
    }
}
