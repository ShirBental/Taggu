package com.taggu.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalIdsTest {

    @Test
    @DisplayName("the same source identity always produces the same id")
    void isDeterministic() {
        assertThat(CanonicalIds.messageId(SourceType.WHATSAPP, "chat", "m1"))
                .isEqualTo(CanonicalIds.messageId(SourceType.WHATSAPP, "chat", "m1"));
    }

    @Test
    @DisplayName("ids are namespaced by source, so two systems cannot collide")
    void separatesSources() {
        assertThat(CanonicalIds.conversationId(SourceType.WHATSAPP, "chat"))
                .isNotEqualTo(CanonicalIds.conversationId(SourceType.TELEGRAM, "chat"));
    }

    @Test
    @DisplayName("ids are namespaced by kind, so a message and a conversation cannot collide")
    void separatesKinds() {
        assertThat(CanonicalIds.conversationId(SourceType.WHATSAPP, "same"))
                .isNotEqualTo(CanonicalIds.participantId(SourceType.WHATSAPP, "same"));
    }
}
