package com.taggu.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.taggu.persistence.view.ConversationSummary;
import com.taggu.persistence.view.MessageView;
import com.taggu.persistence.view.ParticipantView;
import com.taggu.persistence.view.SearchHit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConversationStoreTest extends PersistenceTestBase {

    @Autowired
    private ConversationStore store;

    @Test
    @DisplayName("stores a conversation with its participants and messages")
    void storesAConversation() {
        StoreFixtures fixture = new StoreFixtures("roof")
                .say("Dana", "The contractor came by")
                .say("Ron", "How much?")
                .say("Dana", "He quoted 1550 for the repair");

        ConversationStore.StoredConversation stored = store.save(fixture.parsed());

        assertThat(stored.parsedMessages()).isEqualTo(3);
        assertThat(stored.newMessages()).isEqualTo(3);

        ConversationSummary summary =
                store.findConversation(stored.conversationId()).orElseThrow();
        assertThat(summary.title()).isEqualTo("Fixture roof");
        assertThat(summary.messageCount()).isEqualTo(3);
        assertThat(summary.participants()).extracting(ParticipantView::displayName).containsExactly("Dana", "Ron");

        List<MessageView> messages = store.findMessages(stored.conversationId(), 10, 0);
        assertThat(messages).extracting(MessageView::text)
                .containsExactly("The contractor came by", "How much?", "He quoted 1550 for the repair");
        assertThat(messages.get(0).senderName()).isEqualTo("Dana");
    }

    @Test
    @DisplayName("importing the same export twice stores nothing the second time")
    void isIdempotent() {
        StoreFixtures fixture = new StoreFixtures("roof").say("Dana", "One").say("Ron", "Two");

        ConversationStore.StoredConversation first = store.save(fixture.parsed());
        ConversationStore.StoredConversation second = store.save(fixture.parsed());

        assertThat(first.newMessages()).isEqualTo(2);
        assertThat(second.newMessages()).isZero();
        assertThat(second.conversationId()).isEqualTo(first.conversationId());
        assertThat(store.countMessages(first.conversationId())).isEqualTo(2);
    }

    @Test
    @DisplayName("a later export of the same thread adds only the new messages")
    void addsOnlyNewMessagesOnReimport() {
        store.save(new StoreFixtures("roof").say("Dana", "One").parsed());

        ConversationStore.StoredConversation second =
                store.save(new StoreFixtures("roof").say("Dana", "One").say("Ron", "Two").parsed());

        assertThat(second.parsedMessages()).isEqualTo(2);
        assertThat(second.newMessages()).isEqualTo(1);
        assertThat(store.countMessages(second.conversationId())).isEqualTo(2);
    }

    @Test
    @DisplayName("search matches text regardless of case")
    void searchesCaseInsensitively() {
        var stored = store.save(new StoreFixtures("roof")
                .say("Dana", "The Contractor quoted 1550")
                .say("Ron", "Fine by me")
                .parsed());

        List<SearchHit> hits = store.search("contractor", null, 10);

        assertThat(hits).singleElement().satisfies(hit -> {
            assertThat(hit.text()).isEqualTo("The Contractor quoted 1550");
            assertThat(hit.conversationId()).isEqualTo(stored.conversationId());
            assertThat(hit.senderName()).isEqualTo("Dana");
        });
    }

    @Test
    @DisplayName("search treats wildcard characters as text, not as a pattern")
    void escapesWildcardsInSearch() {
        store.save(new StoreFixtures("sale").say("Dana", "50% off today").say("Ron", "5 off").parsed());

        assertThat(store.search("50%", null, 10)).hasSize(1);
        assertThat(store.search("%", null, 10)).hasSize(1);
        assertThat(store.search("_", null, 10)).isEmpty();
    }

    @Test
    @DisplayName("search can be narrowed to one conversation")
    void scopesSearchToAConversation() {
        var roof = store.save(new StoreFixtures("roof").say("Dana", "quoted 1550").parsed());
        store.save(new StoreFixtures("car").say("Ron", "quoted 900").parsed());

        assertThat(store.search("quoted", null, 10)).hasSize(2);
        assertThat(store.search("quoted", roof.conversationId(), 10)).hasSize(1);
    }

    @Test
    @DisplayName("messages page in time order")
    void pagesMessages() {
        var stored = store.save(new StoreFixtures("roof")
                .say("Dana", "One")
                .say("Ron", "Two")
                .say("Dana", "Three")
                .parsed());

        assertThat(store.findMessages(stored.conversationId(), 2, 0)).extracting(MessageView::text)
                .containsExactly("One", "Two");
        assertThat(store.findMessages(stored.conversationId(), 2, 2)).extracting(MessageView::text)
                .containsExactly("Three");
    }
}
