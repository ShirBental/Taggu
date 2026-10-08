package com.taggu.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.taggu.core.knowledge.ActionItem;
import com.taggu.core.knowledge.ActionItemStatus;
import com.taggu.core.knowledge.Decision;
import com.taggu.core.knowledge.Event;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Fact;
import com.taggu.core.knowledge.Provenance;
import com.taggu.core.knowledge.TimePrecision;
import com.taggu.persistence.view.SearchHit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Full-text search, on the only database that can run it. */
class SearchStorePostgresTest extends PostgresPersistenceTestBase {

    @Autowired
    private ConversationStore conversationStore;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Autowired
    private SearchStore search;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    @DisplayName("finds a message by a word in it, whatever the case, with who said it and where")
    void findsMessagesCaseInsensitively() {
        var stored = conversationStore.save(new StoreFixtures("roof")
                .say("Dana", "The Contractor quoted 1550")
                .say("Ron", "Fine by me")
                .parsed());

        assertThat(search.search("contractor", null, 10)).singleElement().satisfies(hit -> {
            assertThat(hit.kind()).isEqualTo("MESSAGE");
            assertThat(hit.text()).isEqualTo("The Contractor quoted 1550");
            assertThat(hit.conversationId()).isEqualTo(stored.conversationId());
            assertThat(hit.conversationTitle()).isEqualTo("Fixture roof");
            assertThat(hit.senderName()).isEqualTo("Dana");
            assertThat(hit.sentAt()).isEqualTo(StoreFixtures.START);
            assertThat(hit.itemId()).isEqualTo(hit.messageId());
            assertThat(hit.sourceMessageIds()).containsExactly(hit.messageId());
        });
    }

    @Test
    @DisplayName("requires every word, in any order, and understands phrases, alternatives and exclusions")
    void readsTheQueryLikeASearchBox() {
        conversationStore.save(new StoreFixtures("roof")
                .say("Dana", "The contractor quoted 1550 for the roof")
                .say("Ron", "The plumber quoted 300")
                .say("Dana", "The roof contractor is late")
                .parsed());

        assertThat(texts("quoted")).hasSize(2);
        assertThat(texts("roof quoted")).containsExactly("The contractor quoted 1550 for the roof");
        assertThat(texts("\"roof contractor\"")).containsExactly("The roof contractor is late");
        assertThat(texts("plumber or late")).hasSize(2);
        assertThat(texts("contractor -late")).containsExactly("The contractor quoted 1550 for the roof");
        assertThat(texts("nothing-like-this")).isEmpty();
    }

    @Test
    @DisplayName("matches whole words, not fragments of them")
    void matchesWholeWords() {
        conversationStore.save(new StoreFixtures("roof").say("Dana", "The contractor called").parsed());

        // The substring search this replaced would have matched both.
        assertThat(texts("contractor")).hasSize(1);
        assertThat(texts("contract")).isEmpty();
    }

    @ParameterizedTest(name = "[{0}]")
    @DisplayName("treats punctuation, wildcards and SQL as text; nothing typed can break the query")
    @ValueSource(strings = {"%", "_", "50%", "'", "\"", "\"unclosed", "a:*", "!!", "(", "x'; DROP TABLE messages; --", "-", "or"})
    void survivesAnyQuery(String query) {
        conversationStore.save(new StoreFixtures("sale").say("Dana", "50% off today").say("Ron", "5 off").parsed());

        assertThatCode(() -> search.search(query, null, 10)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("a wildcard character matches nothing by itself, and a number beside one still matches")
    void wildcardsAreNotPatterns() {
        conversationStore.save(new StoreFixtures("sale").say("Dana", "50% off today").say("Ron", "5 off").parsed());

        assertThat(search.search("%", null, 10)).isEmpty();
        assertThat(search.search("_", null, 10)).isEmpty();
        assertThat(texts("50%")).containsExactly("50% off today");
    }

    @Test
    @DisplayName("can be narrowed to one conversation, for messages and knowledge alike")
    void scopesToAConversation() {
        StoreFixtures roofFixture = new StoreFixtures("roof").say("Dana", "quoted 1550");
        var roof = conversationStore.save(roofFixture.parsed());
        StoreFixtures carFixture = new StoreFixtures("car").say("Ron", "quoted 900");
        var car = conversationStore.save(carFixture.parsed());
        knowledgeStore.save(roof.conversationId(), fact("Roof was quoted at 1550", roofFixture.message(0).id()));
        knowledgeStore.save(car.conversationId(), fact("Car was quoted at 900", carFixture.message(0).id()));

        assertThat(search.search("quoted", null, 10)).hasSize(4);
        assertThat(search.search("quoted", roof.conversationId(), 10))
                .hasSize(2)
                .allSatisfy(hit -> assertThat(hit.conversationId()).isEqualTo(roof.conversationId()));
    }

    @Test
    @DisplayName("finds every kind of knowledge, and returns the evidence behind it")
    void findsKnowledgeWithItsEvidence() {
        StoreFixtures fixture = new StoreFixtures("roof")
                .say("Dana", "first")
                .say("Ron", "second")
                .say("Dana", "third");
        UUID conversationId = conversationStore.save(fixture.parsed()).conversationId();
        UUID first = fixture.message(0).id();
        UUID second = fixture.message(1).id();
        knowledgeStore.save(
                conversationId,
                new ExtractionResult(
                        "test-extractor",
                        List.of(),
                        List.of(),
                        List.of(new Fact("Giraffe fact", null, 0.5, Provenance.ofMessages(second, first))),
                        List.of(new Event(
                                "Giraffe event",
                                null,
                                null,
                                TimePrecision.UNRESOLVED,
                                null,
                                "Zebra hall",
                                0.5,
                                Provenance.ofMessages(second))),
                        List.of(new ActionItem(
                                "Giraffe action",
                                null,
                                null,
                                TimePrecision.UNRESOLVED,
                                null,
                                ActionItemStatus.OPEN,
                                0.5,
                                Provenance.ofMessages(first))),
                        List.of(new Decision("Giraffe decision", null, 0.5, Provenance.ofMessages(first)))));

        List<SearchHit> hits = search.search("giraffe", null, 10);

        assertThat(hits).extracting(SearchHit::kind)
                .containsExactlyInAnyOrder("FACT", "EVENT", "ACTION_ITEM", "DECISION");
        SearchHit fact = hits.stream().filter(hit -> hit.kind().equals("FACT")).findFirst().orElseThrow();
        assertThat(fact.text()).isEqualTo("Giraffe fact");
        // Evidence comes back oldest first, and the fields a message hit has are filled from the earliest.
        assertThat(fact.sourceMessageIds()).containsExactly(first, second);
        assertThat(fact.messageId()).isEqualTo(first);
        assertThat(fact.sentAt()).isEqualTo(StoreFixtures.START);
        assertThat(fact.senderName()).isNull();
        assertThat(fact.itemId()).isNotEqualTo(first);
        assertThat(fact.conversationTitle()).isEqualTo("Fixture roof");
        // An event is also found by where it happens.
        assertThat(search.search("zebra", null, 10)).extracting(SearchHit::kind).containsExactly("EVENT");
    }

    @Test
    @DisplayName("puts better matches first and honours the limit")
    void ranksAndLimits() {
        conversationStore.save(new StoreFixtures("roof")
                .say("Dana", "roof")
                .say("Ron", "we talked about many things today and at some point also the roof came up briefly")
                .say("Dana", "roof roof roof")
                .parsed());

        assertThat(texts("roof")).first().isEqualTo("roof roof roof");
        assertThat(search.search("roof", null, 2)).hasSize(2);
    }

    @Test
    @DisplayName("does not stem: a word finds itself, not its other forms")
    void doesNotStem() {
        conversationStore.save(new StoreFixtures("roof").say("Dana", "He quoted two prices").parsed());

        assertThat(texts("quoted")).hasSize(1);
        assertThat(texts("quote")).isEmpty();
        assertThat(texts("quotes")).isEmpty();
        assertThat(texts("prices")).hasSize(1);
        assertThat(texts("price")).isEmpty();
    }

    @Test
    @DisplayName("splits numbers, hyphenated words, contractions and links the way PostgreSQL's parser does")
    void tokenisesPredictably() {
        conversationStore.save(new StoreFixtures("mixed")
                .say("Dana", "He quoted $1,550")
                .say("Ron", "A follow-up call")
                .say("Dana", "Let's go")
                .say("Ron", "See https://example.com/roof?id=42")
                .say("Dana", "Meet at the caf\u00e9")
                .parsed());

        // A number with a separator is two words that must be adjacent; it is not the same number without.
        assertThat(texts("1,550")).containsExactly("He quoted $1,550");
        assertThat(texts("550")).containsExactly("He quoted $1,550");
        assertThat(texts("1550")).isEmpty();
        // A hyphenated word is found whole or by either part, but not run together.
        assertThat(texts("follow-up")).hasSize(1);
        assertThat(texts("follow")).hasSize(1);
        assertThat(texts("followup")).isEmpty();
        // A contraction is split at the apostrophe.
        assertThat(texts("let")).hasSize(1);
        assertThat(texts("let's")).hasSize(1);
        assertThat(texts("lets")).isEmpty();
        // A link is found by its host, not by words inside its path.
        assertThat(texts("example.com")).hasSize(1);
        assertThat(texts("roof")).isEmpty();
        // Accents are significant.
        assertThat(texts("caf\u00e9")).hasSize(1);
        assertThat(texts("cafe")).isEmpty();
    }

    @Test
    @DisplayName("ranks by how often and how close together the words appear, then by recency; length does not count")
    void ranksByFrequencyProximityThenRecency() {
        conversationStore.save(new StoreFixtures("roof")
                .say("Dana", "roof")
                .say("Ron", "a long message that goes on about many other things and mentions the roof only once")
                .say("Dana", "the roof and, a good while later in the same message, the contractor")
                .say("Ron", "roof contractor")
                .parsed());

        // One occurrence each: the short message does not outrank the long ones, so the newest comes first.
        List<SearchHit> single = search.search("roof -contractor", null, 10);
        assertThat(single).extracting(SearchHit::rank).containsOnly(single.get(0).rank());
        assertThat(single).extracting(SearchHit::text)
                .containsExactly(
                        "a long message that goes on about many other things and mentions the roof only once", "roof");
        // Both words adjacent beats both words far apart.
        assertThat(texts("roof contractor"))
                .containsExactly(
                        "roof contractor", "the roof and, a good while later in the same message, the contractor");
    }

    @Test
    @DisplayName("works the same for text that is not English")
    void isLanguageNeutral() {
        conversationStore.save(new StoreFixtures("mixed")
                .say("Dana", "\u05e9\u05dc\u05d5\u05dd \u05dc\u05db\u05d5\u05dc\u05dd")
                .say("Ron", "Gr\u00fc\u00dfe aus M\u00fcnchen")
                .parsed());

        assertThat(texts("\u05e9\u05dc\u05d5\u05dd")).hasSize(1);
        assertThat(texts("m\u00fcnchen")).hasSize(1);
    }

    @Test
    @DisplayName("is answered from the index rather than by reading every message")
    void usesTheIndex() {
        conversationStore.save(new StoreFixtures("roof").say("Dana", "roof").parsed());

        // With one row the planner would rightly prefer reading the table, so that choice is taken away
        // for this transaction; what is being checked is that the index can answer the query at all.
        List<String> plan = transaction.execute(status -> {
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList(
                    "EXPLAIN SELECT id FROM messages WHERE search_vector @@ websearch_to_tsquery('simple', 'roof')",
                    String.class);
        });

        assertThat(String.join("\n", plan)).contains("idx_messages_search");
    }

    private List<String> texts(String query) {
        return search.search(query, null, 10).stream().map(SearchHit::text).toList();
    }

    private static ExtractionResult fact(String statement, UUID source) {
        return new ExtractionResult(
                "test-extractor",
                List.of(),
                List.of(),
                List.of(new Fact(statement, null, 0.5, Provenance.ofMessages(source))),
                List.of(),
                List.of(),
                List.of());
    }
}
