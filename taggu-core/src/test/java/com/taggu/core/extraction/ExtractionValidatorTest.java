package com.taggu.core.extraction;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.taggu.core.model.Participant;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExtractionValidatorTest {

    private static final Participant SENDER = TestMessages.participant("Dana");

    private final ExtractionValidator validator = new ExtractionValidator();

    private final List<CanonicalMessage> messages =
            TestMessages.sequence(SENDER, Instant.parse("2024-03-14T17:00:00Z"), "a", "b", "c", "d");

    /** Lead-in is "a" and "b"; focus is "c" and "d". */
    private final ExtractionContext window = new ExtractionContext(
            TestMessages.conversation(List.of(SENDER)), messages.subList(0, 2), messages.subList(2, 4), ZoneId.of("UTC"));

    private final UUID leadIn = messages.get(0).id();
    private final UUID focus = messages.get(2).id();
    private final UUID neverShown = UUID.randomUUID();

    @Test
    @DisplayName("keeps knowledge that cites the window it came from")
    void keepsGroundedKnowledge() {
        ExtractionResult result = everyKind(Provenance.ofMessages(focus));

        ExtractionValidator.Validated validated = validator.validate(window, result);

        assertThat(validated.rejected()).isZero();
        assertThat(validated.result()).isEqualTo(result);
    }

    @Test
    @DisplayName("lead-in may be cited as long as a focus message is cited with it")
    void keepsKnowledgeCitingLeadInAndFocusTogether() {
        ExtractionValidator.Validated validated =
                validator.validate(window, everyKind(Provenance.ofMessages(leadIn, focus)));

        assertThat(validated.rejected()).isZero();
        assertThat(validated.result().size()).isEqualTo(6);
    }

    @Test
    @DisplayName("drops every kind of item that cites a message the extractor was never shown")
    void rejectsEvidenceFromOutsideTheWindow() {
        ExtractionValidator.Validated validated =
                validator.validate(window, everyKind(Provenance.ofMessages(neverShown)));

        assertThat(validated.rejected()).isEqualTo(6);
        assertThat(validated.result().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("one invented citation among real ones is enough to drop the item")
    void rejectsPartlyInventedEvidence() {
        ExtractionResult result = facts(
                new Fact("grounded", null, 0.5, Provenance.ofMessages(focus)),
                new Fact("half invented", null, 0.5, Provenance.ofMessages(focus, neverShown)));

        ExtractionValidator.Validated validated = validator.validate(window, result);

        assertThat(validated.rejected()).isEqualTo(1);
        assertThat(validated.result().facts()).extracting(Fact::statement).containsExactly("grounded");
    }

    @Test
    @DisplayName("drops knowledge resting on lead-in alone, which an earlier window already owns")
    void rejectsKnowledgeCitingOnlyLeadIn() {
        ExtractionResult result = facts(new Fact("belongs to the previous window", null, 0.5, Provenance.ofMessages(leadIn)));

        ExtractionValidator.Validated validated = validator.validate(window, result);

        assertThat(validated.rejected()).isEqualTo(1);
        assertThat(validated.result().facts()).isEmpty();
    }

    @Test
    @DisplayName("keeps the extractor's name on what survives")
    void keepsTheExtractorName() {
        assertThat(validator.validate(window, everyKind(Provenance.ofMessages(neverShown))).result().extractorName())
                .isEqualTo("test-extractor");
    }

    private static ExtractionResult facts(Fact... facts) {
        return new ExtractionResult(
                "test-extractor", List.of(), List.of(), List.of(facts), List.of(), List.of(), List.of());
    }

    private static ExtractionResult everyKind(Provenance provenance) {
        return new ExtractionResult(
                "test-extractor",
                List.of(Tag.of("house", 0.5, provenance)),
                List.of(ExtractedEntity.of(EntityType.ORGANIZATION, "Nir Levy Ltd", 0.5, provenance)),
                List.of(new Fact("He quoted $1,550", null, 0.5, provenance)),
                List.of(new Event("Dinner", null, null, TimePrecision.UNRESOLVED, "Sunday", null, 0.5, provenance)),
                List.of(new ActionItem(
                        "call him", "Ron", null, TimePrecision.UNRESOLVED, null, ActionItemStatus.OPEN, 0.5, provenance)),
                List.of(new Decision("Let's go with them", null, 0.5, provenance)));
    }
}
