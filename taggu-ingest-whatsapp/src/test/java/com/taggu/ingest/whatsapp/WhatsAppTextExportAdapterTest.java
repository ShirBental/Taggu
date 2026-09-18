package com.taggu.ingest.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;

import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.MessageType;
import com.taggu.core.model.Participant;
import com.taggu.core.model.SourceType;
import com.taggu.core.source.ParseIssue;
import com.taggu.core.source.ParsedConversation;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WhatsAppTextExportAdapterTest {

    @Test
    @DisplayName("reads ordinary messages with sender, timestamp and text")
    void readsNormalMessages() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("normal-android.txt");

        assertThat(parsed.messages()).hasSize(3);
        assertThat(parsed.issues()).noneMatch(issue -> issue.severity() == ParseIssue.Severity.SKIPPED);

        CanonicalMessage first = parsed.messages().get(0);
        assertThat(first.source()).isEqualTo(SourceType.WHATSAPP);
        assertThat(first.text()).isEqualTo("Morning!");
        assertThat(first.messageType()).isEqualTo(MessageType.TEXT);
        assertThat(first.sentAt())
                .isEqualTo(LocalDateTime.of(2024, 3, 12, 9, 15).atZone(WhatsAppFixtures.ZONE).toInstant());
        assertThat(senderName(parsed, first)).isEqualTo("Dana");
    }

    @Test
    @DisplayName("keeps the following lines of a multiline message with the message")
    void readsMultilineMessages() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("multiline.txt");

        assertThat(parsed.messages()).hasSize(2);
        CanonicalMessage list = parsed.messages().get(0);
        assertThat(list.text())
                .isEqualTo(
                        """
                        Here is the list:
                        - bread
                        - milk
                        - the thing you asked for

                        Let me know if I missed anything"""
                                .stripTrailing());
        assertThat(list.messageType()).isEqualTo(MessageType.TEXT);
        assertThat(parsed.messages().get(1).text()).isEqualTo("That covers it");
    }

    @Test
    @DisplayName("skips lines it cannot read and keeps importing the rest")
    void toleratesMalformedLines() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("malformed.txt");

        assertThat(parsed.messages()).extracting(CanonicalMessage::text)
                .containsExactly("First real message", "Last real message");
        assertThat(parsed.issues()).hasSize(3);
        assertThat(parsed.issues()).allSatisfy(issue ->
                assertThat(issue.severity()).isEqualTo(ParseIssue.Severity.SKIPPED));
        assertThat(parsed.issues()).extracting(ParseIssue::lineNumber).containsExactly(1, 3, 4);
    }

    @Test
    @DisplayName("an unreadable line is reported without its content in the loggable summary")
    void issueSummaryCarriesNoContent() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("malformed.txt");

        ParseIssue issue = parsed.issues().get(1);
        assertThat(issue.rawLine()).contains("Impossible date");
        assertThat(issue.summary()).doesNotContain("Impossible date").contains("line 3");
    }

    @Test
    @DisplayName("marks platform notices as system messages and deletions as deleted")
    void classifiesSystemMessages() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("system-messages.txt");

        List<CanonicalMessage> messages = parsed.messages();
        assertThat(messages).hasSize(6);
        assertThat(messages.subList(0, 3)).allSatisfy(message -> {
            assertThat(message.messageType()).isEqualTo(MessageType.SYSTEM);
            assertThat(message.senderId()).isNull();
        });
        assertThat(messages.get(3).messageType()).isEqualTo(MessageType.TEXT);
        assertThat(messages.get(5).messageType()).isEqualTo(MessageType.DELETED);
        // Only the people who actually spoke become participants.
        assertThat(parsed.conversation().participants()).extracting(Participant::displayName)
                .containsExactly("Dana", "Ron");
    }

    @Test
    @DisplayName("collects every participant that speaks, once each")
    void collectsParticipants() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("multi-participant.txt");

        assertThat(parsed.conversation().participants()).extracting(Participant::displayName)
                .containsExactly("Dana", "Ron", "Yael", "Avi");
        assertThat(parsed.conversation().participants()).extracting(Participant::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("keeps punctuation and URLs intact, including colons inside the text")
    void keepsPunctuationAndUrls() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("punctuation-and-urls.txt");

        assertThat(parsed.messages()).extracting(CanonicalMessage::text)
                .containsExactly(
                        "Look at this: https://example.com/a/b?c=1&d=2",
                        "Ha! Amazing... where did you find it?",
                        "Note: it's from the newsletter (the one I told you about)",
                        "50% off?! No way");
        assertThat(senderName(parsed, parsed.messages().get(2))).isEqualTo("Dana");
    }

    @Test
    @DisplayName("keeps consecutive messages separate and in order, so context can be read across them")
    void keepsContextDependentMessagesInOrder() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("context-dependent.txt");

        assertThat(parsed.messages()).extracting(CanonicalMessage::text)
                .containsExactly("Want to come Sunday?", "Sure", "Around 4?", "Perfect");
        assertThat(parsed.messages()).isSortedAccordingTo(
                (left, right) -> left.sentAt().compareTo(right.sentAt()));
    }

    @Test
    @DisplayName("reads the iOS export shape, invisible marks and all")
    void readsIosExports() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("ios-format.txt");

        assertThat(parsed.messages()).hasSize(4);
        assertThat(parsed.messages().get(0).text()).isEqualTo("Sent from an iPhone");
        assertThat(parsed.messages().get(0).sentAt())
                .isEqualTo(LocalDateTime.of(2024, 12, 3, 14, 32, 11).atZone(WhatsAppFixtures.ZONE).toInstant());
        assertThat(parsed.messages().get(2).messageType()).isEqualTo(MessageType.MEDIA);
        assertThat(parsed.messages().get(3).text()).isEqualTo("Line one\nand a second line of the same message");
    }

    @Test
    @DisplayName("infers month-first dates from a day above 12 in the file")
    void infersMonthFirstDatesFromTheFile() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("us-format.txt");

        // 12/25/24 can only be December 25th, which settles the order for the whole export.
        assertThat(parsed.messages().get(0).sentAt())
                .isEqualTo(LocalDateTime.of(2024, 3, 12, 14, 32).atZone(WhatsAppFixtures.ZONE).toInstant());
        assertThat(parsed.messages().get(2).sentAt())
                .isEqualTo(LocalDateTime.of(2024, 12, 25, 19, 0).atZone(WhatsAppFixtures.ZONE).toInstant());
    }

    @Test
    @DisplayName("warns rather than guesses silently when no date settles the day/month order")
    void warnsWhenEveryDateIsAmbiguous() throws IOException {
        String export = "01/02/2024, 09:15 - Dana: Ambiguous date\n";
        ParsedConversation parsed = new WhatsAppTextExportAdapter()
                .parse(new StringReader(export), com.taggu.core.source.ImportRequest.of(
                        "ambiguous", "Ambiguous", ZoneId.of("UTC")));

        assertThat(parsed.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.severity()).isEqualTo(ParseIssue.Severity.WARNING);
            assertThat(issue.reason()).contains("DAY_FIRST");
        });
        assertThat(parsed.messages().get(0).sentAt())
                .isEqualTo(LocalDateTime.of(2024, 2, 1, 9, 15).atZone(ZoneId.of("UTC")).toInstant());
    }

    @Test
    @DisplayName("gives the same ids to the same export every time, so re-importing is a no-op")
    void derivesStableIds() throws IOException {
        ParsedConversation first = WhatsAppFixtures.parse("normal-android.txt");
        ParsedConversation second = WhatsAppFixtures.parse("normal-android.txt");

        assertThat(first.conversation().id()).isEqualTo(second.conversation().id());
        assertThat(first.messages()).extracting(CanonicalMessage::id)
                .isEqualTo(second.messages().stream().map(CanonicalMessage::id).toList());
    }

    @Test
    @DisplayName("keeps the original line so an import can be re-examined")
    void preservesRawContent() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("normal-android.txt");

        assertThat(parsed.messages().get(0).rawContent()).isEqualTo("12/03/2024, 09:15 - Dana: Morning!");
    }

    private static String senderName(ParsedConversation parsed, CanonicalMessage message) {
        return parsed.conversation().participants().stream()
                .filter(participant -> participant.id().equals(message.senderId()))
                .map(Participant::displayName)
                .findFirst()
                .orElse(null);
    }
}
