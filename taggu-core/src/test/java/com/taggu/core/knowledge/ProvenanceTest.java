package com.taggu.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Provenance is the system's one non-negotiable rule, so these tests are about what the domain model
 * refuses to build rather than about what it builds.
 */
class ProvenanceTest {

    private static final UUID MESSAGE = UUID.randomUUID();

    @Test
    @DisplayName("provenance cannot be empty")
    void rejectsEmptyProvenance() {
        assertThatThrownBy(() -> new Provenance(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one source message");

        assertThatThrownBy(() -> new Provenance(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no kind of knowledge can be built without evidence")
    void rejectsKnowledgeWithoutProvenance() {
        assertThatThrownBy(() -> new Fact("the roof costs 1550", null, 0.5, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                        new Event("dinner", Instant.now(), null, TimePrecision.EXACT, "sunday", null, 0.5, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ActionItem(
                        "call the plumber",
                        null,
                        null,
                        TimePrecision.UNRESOLVED,
                        null,
                        ActionItemStatus.OPEN,
                        0.5,
                        null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Decision("we are going with them", Instant.now(), 0.5, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("evidence keeps its order and drops repeats when listed as ids")
    void listsCitedMessagesInOrder() {
        UUID other = UUID.randomUUID();
        Provenance provenance = new Provenance(List.of(
                MessageEvidence.of(MESSAGE), new MessageEvidence(other, "around 4"), MessageEvidence.of(MESSAGE)));

        assertThat(provenance.messageIds()).containsExactly(MESSAGE, other);
    }

    @Test
    @DisplayName("confidence outside 0..1 is rejected")
    void rejectsImpossibleConfidence() {
        Provenance provenance = Provenance.ofMessages(MESSAGE);
        assertThatThrownBy(() -> new Fact("statement", null, 1.5, provenance))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Fact("statement", null, -0.1, provenance))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
