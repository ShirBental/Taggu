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
    @DisplayName("an iOS notice attributed to a sender is a system message, and that sender is nobody")
    void iosNoticesAreNotMessagesFromAParticipant() throws IOException {
        // iOS writes notices as "Group name: <mark>text". \u200E is the mark, \u202F the space before PM.
        String export = "[3/14/24, 9:05:10\u202FPM] Dana: hello\n"
                + "[3/14/24, 9:06:00\u202FPM] Hike Group: \u200EDana created group \"Hike Group\"\n"
                + "[3/14/24, 9:07:00\u202FPM] Hike Group: \u200EDana added Ron\n"
                + "[3/14/24, 9:08:00\u202FPM] Ron: \u200ERon left\n"
                + "[3/14/24, 9:09:00\u202FPM] Dana: \u200Eimage omitted\n";

        ParsedConversation parsed = parse(export, ZoneId.of("UTC"));

        assertThat(parsed.messages()).extracting(CanonicalMessage::messageType)
                .containsExactly(
                        MessageType.TEXT, MessageType.SYSTEM, MessageType.SYSTEM, MessageType.SYSTEM, MessageType.MEDIA);
        assertThat(parsed.messages().subList(1, 4)).allSatisfy(notice -> assertThat(notice.senderId()).isNull());
        assertThat(parsed.messages().get(2).text()).isEqualTo("Hike Group: Dana added Ron");
        // "Hike Group" never spoke, and Ron only appears in notices.
        assertThat(parsed.conversation().participants()).extracting(Participant::displayName)
                .containsExactly("Dana");
    }

    @Test
    @DisplayName("a person's message that merely sounds like a notice stays a message")
    void noticeWordingAloneDoesNotMakeASystemMessage() throws IOException {
        String export = "[3/14/24, 9:05:10\u202FPM] Dana: I added sugar and left it on the counter\n"
                + "[3/14/24, 9:06:00\u202FPM] Ron: \u200EThanks!\n";

        ParsedConversation parsed = parse(export, ZoneId.of("UTC"));

        // The first has the wording but no platform mark; the second has the mark but not the wording.
        assertThat(parsed.messages()).extracting(CanonicalMessage::messageType)
                .containsExactly(MessageType.TEXT, MessageType.TEXT);
        assertThat(parsed.messages()).extracting(message -> senderName(parsed, message))
                .containsExactly("Dana", "Ron");
    }

    @Test
    @DisplayName("removes WhatsApp's edited marker from the text and remembers it as metadata")
    void stripsTheEditedMarker() throws IOException {
        String export = "[3/14/24, 9:05:10\u202FPM] Dana: I added sugar \u200E<This message was edited>\n"
                + "[3/14/24, 9:06:00\u202FPM] Ron: Two lines\nthen edited <This message was edited>\n"
                + "[3/14/24, 9:07:00\u202FPM] Dana: Never edited\n";

        ParsedConversation parsed = parse(export, ZoneId.of("UTC"));

        assertThat(parsed.messages()).extracting(CanonicalMessage::text)
                .containsExactly("I added sugar", "Two lines\nthen edited", "Never edited");
        assertThat(parsed.messages().get(0).metadata()).containsEntry("edited", "true");
        assertThat(parsed.messages().get(1).metadata()).containsEntry("edited", "true");
        assertThat(parsed.messages().get(2).metadata()).doesNotContainKey("edited");
        assertThat(parsed.messages().get(0).messageType()).isEqualTo(MessageType.TEXT);
        // The untouched line is still there for anyone who needs to see what the export said.
        assertThat(parsed.messages().get(0).rawContent()).contains("<This message was edited>");
    }

    @Test
    @DisplayName("drops the tilde WhatsApp puts before the name of someone not in the contacts")
    void stripsTheUnsavedContactTilde() throws IOException {
        String export = "[3/14/24, 9:05:10\u202FPM] ~\u202FNoa: Hi, I'm new here\n"
                + "[3/14/24, 9:06:00\u202FPM] Noa: Saved now\n"
                + "[3/14/24, 9:07:00\u202FPM] ~ Avi: Sometimes it is a plain space\n";

        ParsedConversation parsed = parse(export, ZoneId.of("UTC"));

        assertThat(parsed.conversation().participants()).extracting(Participant::displayName)
                .containsExactly("Noa", "Avi");
        assertThat(parsed.messages().get(0).senderId()).isEqualTo(parsed.messages().get(1).senderId());
    }

    @Test
    @DisplayName("reads times written with dots instead of colons")
    void readsDottedTimes() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("dotted-times.txt");

        assertThat(parsed.issues()).isEmpty();
        assertThat(parsed.messages()).extracting(CanonicalMessage::text)
                .containsExactly("Morning!", "Evening", "Still up");
        assertThat(parsed.messages()).extracting(CanonicalMessage::sentAt)
                .containsExactly(
                        LocalDateTime.of(2024, 3, 12, 9, 15).atZone(WhatsAppFixtures.ZONE).toInstant(),
                        LocalDateTime.of(2024, 3, 12, 21, 5).atZone(WhatsAppFixtures.ZONE).toInstant(),
                        LocalDateTime.of(2024, 3, 13, 0, 10, 30).atZone(WhatsAppFixtures.ZONE).toInstant());
    }

    @Test
    @DisplayName("reads past a byte-order mark and Windows line endings")
    void readsByteOrderMarkAndWindowsLineEndings() throws IOException {
        String export = "\uFEFF12/03/2024, 09:15 - Dana: Morning!\r\n12/03/2024, 09:16 - Ron: Hi\r\n";

        ParsedConversation parsed = parse(export, ZoneId.of("UTC"));

        assertThat(parsed.issues()).noneMatch(issue -> issue.severity() == ParseIssue.Severity.SKIPPED);
        assertThat(parsed.messages()).extracting(CanonicalMessage::text).containsExactly("Morning!", "Hi");
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
    @DisplayName("gives a message the same id whatever zone the export is read in")
    void idsDoNotDependOnTheImportZone() throws IOException {
        String export = "12/03/2024, 23:30 - Dana: Late one\n13/03/2024, 00:10 - Ron: Later still\n";

        ParsedConversation utc = parse(export, ZoneId.of("UTC"));
        ParsedConversation berlin = parse(export, ZoneId.of("Europe/Berlin"));
        ParsedConversation auckland = parse(export, ZoneId.of("Pacific/Auckland"));

        // The zone moves the instant, as it should...
        assertThat(berlin.messages().get(0).sentAt()).isNotEqualTo(utc.messages().get(0).sentAt());
        // ...but not the identity, or re-importing under another zone would store everything twice.
        assertThat(berlin.messages()).extracting(CanonicalMessage::id)
                .isEqualTo(utc.messages().stream().map(CanonicalMessage::id).toList());
        assertThat(auckland.messages()).extracting(CanonicalMessage::sourceMessageId)
                .isEqualTo(utc.messages().stream().map(CanonicalMessage::sourceMessageId).toList());
    }

    @Test
    @DisplayName("keeps the ids that exports imported under UTC were given before ids ignored the zone")
    void idsOfUtcImportsAreUnchanged() throws IOException {
        ParsedConversation parsed = parse("12/03/2024, 09:15 - Dana: Morning!\n", ZoneId.of("UTC"));

        // sha256("2024-03-12T09:15:00Z|<Dana's participant id>|Morning!"), first 16 hex characters:
        // the identity string the adapter has always used for a UTC import, written out by hand.
        String dana = com.taggu.core.model.CanonicalIds.participantId(SourceType.WHATSAPP, "Dana").toString();
        assertThat(parsed.messages().get(0).sourceMessageId())
                .isEqualTo(sha256Hex("2024-03-12T09:15:00Z|" + dana + "|Morning!").substring(0, 16));
    }

    @Test
    @DisplayName("keeps distinct messages sent in the same second apart, and in file order")
    void sameSecondMessagesStayDistinct() throws IOException {
        String export = "[3/14/24, 9:05:10\u202FPM] Dana: Want to come Sunday?\n"
                + "[3/14/24, 9:05:10\u202FPM] Ron: Sure\n"
                + "[3/14/24, 9:05:10\u202FPM] Dana: Around 4?\n"
                + "[3/14/24, 9:05:10\u202FPM] Ron: Sure\n";

        ParsedConversation parsed = parse(export, ZoneId.of("UTC"));

        assertThat(parsed.messages()).extracting(CanonicalMessage::text)
                .containsExactly("Want to come Sunday?", "Sure", "Around 4?", "Sure");
        assertThat(parsed.messages()).extracting(CanonicalMessage::sentAt).containsOnly(parsed.messages().get(0).sentAt());
        // Even Ron's two identical messages, same second, same text, are two messages with two ids.
        assertThat(parsed.messages()).extracting(CanonicalMessage::id).doesNotHaveDuplicates();
        assertThat(parsed.messages()).extracting(CanonicalMessage::sourceMessageId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("keeps messages sharing a minute apart when the export has no seconds at all")
    void sameMinuteMessagesStayDistinct() throws IOException {
        String export = "14/03/2024, 18:03 - Yael: ok\n"
                + "14/03/2024, 18:03 - Mom: ok\n"
                + "14/03/2024, 18:03 - Yael: ok\n"
                + "14/03/2024, 18:03 - Yael: ok\n";

        ParsedConversation parsed = parse(export, ZoneId.of("UTC"));

        assertThat(parsed.messages()).hasSize(4);
        assertThat(parsed.messages()).extracting(CanonicalMessage::id).doesNotHaveDuplicates();
        assertThat(parsed.messages()).extracting(message -> senderName(parsed, message))
                .containsExactly("Yael", "Mom", "Yael", "Yael");
    }

    @Test
    @DisplayName("gives repeated identical messages the same ids on every import, also when the export has grown")
    void repeatedMessagesKeepTheirIdsAcrossImports() throws IOException {
        String export = "14/03/2024, 18:03 - Yael: ok\n14/03/2024, 18:03 - Yael: ok\n";

        List<String> first = ids(parse(export, ZoneId.of("UTC")));
        List<String> again = ids(parse(export, ZoneId.of("UTC")));
        List<String> grown = ids(parse(export + "14/03/2024, 18:03 - Yael: ok\n14/03/2024, 18:09 - Mom: bye\n", ZoneId.of("UTC")));

        assertThat(again).isEqualTo(first);
        assertThat(grown.subList(0, 2)).isEqualTo(first);
        assertThat(grown).doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    @DisplayName("keeps the original line so an import can be re-examined")
    void preservesRawContent() throws IOException {
        ParsedConversation parsed = WhatsAppFixtures.parse("normal-android.txt");

        assertThat(parsed.messages().get(0).rawContent()).isEqualTo("12/03/2024, 09:15 - Dana: Morning!");
    }

    private static ParsedConversation parse(String export, ZoneId zone) throws IOException {
        return new WhatsAppTextExportAdapter()
                .parse(new StringReader(export), com.taggu.core.source.ImportRequest.of("inline", "Inline", zone));
    }

    private static List<String> ids(ParsedConversation parsed) {
        return parsed.messages().stream().map(CanonicalMessage::sourceMessageId).toList();
    }

    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String senderName(ParsedConversation parsed, CanonicalMessage message) {
        return parsed.conversation().participants().stream()
                .filter(participant -> participant.id().equals(message.senderId()))
                .map(Participant::displayName)
                .findFirst()
                .orElse(null);
    }
}
