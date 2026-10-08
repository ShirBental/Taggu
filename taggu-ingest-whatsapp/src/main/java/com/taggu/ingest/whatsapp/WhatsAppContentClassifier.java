package com.taggu.ingest.whatsapp;

import com.taggu.core.model.MessageType;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides what a WhatsApp message body actually is.
 *
 * <p>Exports replace non-text content with placeholder sentences, so "image omitted" has to be
 * classified as media rather than stored as something a participant wrote. Getting this wrong would
 * feed placeholder text to the extraction layer as if it were conversation.
 */
public final class WhatsAppContentClassifier {

    private static final Pattern MEDIA = Pattern.compile(
            "^(<media omitted>|(image|video|audio|voice message|sticker|gif|photo|animation)"
                    + "( file)? omitted\\.?)$");

    private static final Pattern ATTACHMENT =
            Pattern.compile("^(<attached:.*>|document omitted\\.?|.*\\(file attached\\))$");

    private static final Pattern DELETED = Pattern.compile(
            "^(this message was deleted\\.?|you deleted this message\\.?|"
                    + "this message was deleted by the admin\\.?)$");

    private static final Pattern CONTACT = Pattern.compile("^(contact card omitted\\.?|.*\\.vcf \\(file attached\\))$");

    private static final Pattern LOCATION = Pattern.compile("^(location: https?://.*|live location shared\\.?)$");

    /**
     * Wording of the notices WhatsApp writes into a chat itself. English only: an export in another
     * language carries these in that language, and they are then read as ordinary text.
     */
    private static final Pattern PLATFORM_NOTICE = Pattern.compile(
            "end-to-end encrypted|created (this )?group|changed the subject|changed (this|the) group|"
                    + "changed the group description|\\badded\\b|\\bremoved\\b|\\bleft\\b|"
                    + "joined using|security code|disappearing messages|pinned a message|changed their phone number");

    private static final String EDITED_MARKER = "<This message was edited>";

    private WhatsAppContentClassifier() {}

    /**
     * True when a body that the export marks as platform-written reads like a notice.
     *
     * <p>Android writes notices with no sender, which is enough to recognise them. iOS writes them as
     * {@code "Group name: Dana added Ron"}, so the only things telling them apart from a message are the
     * platform mark in front of the text and the wording. Both are required: the wording alone would
     * turn "I added sugar" into a system message.
     */
    public static boolean isPlatformNotice(String text) {
        return text != null && PLATFORM_NOTICE.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /** True when WhatsApp appended its "edited" marker to the body. */
    public static boolean isEdited(String body) {
        return body.stripTrailing().endsWith(EDITED_MARKER);
    }

    /** The body without WhatsApp's "edited" marker, which is the app's annotation and not the sender's words. */
    public static String withoutEditedMarker(String body) {
        String trimmed = body.stripTrailing();
        return trimmed.endsWith(EDITED_MARKER)
                ? trimmed.substring(0, trimmed.length() - EDITED_MARKER.length()).stripTrailing()
                : body;
    }

    /** Classifies a message written by a participant. */
    public static MessageType classify(String text) {
        if (text == null || text.isBlank()) {
            return MessageType.UNKNOWN;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (DELETED.matcher(normalized).matches()) {
            return MessageType.DELETED;
        }
        if (LOCATION.matcher(normalized).matches()) {
            return MessageType.LOCATION;
        }
        if (CONTACT.matcher(normalized).matches()) {
            return MessageType.CONTACT;
        }
        if (ATTACHMENT.matcher(normalized).matches()) {
            return MessageType.ATTACHMENT;
        }
        if (MEDIA.matcher(normalized).matches()) {
            return MessageType.MEDIA;
        }
        return MessageType.TEXT;
    }

    /**
     * True when a message carries no words of its own, so the extraction layer should skip it rather
     * than read a placeholder as something someone said.
     */
    public static boolean isPlaceholder(MessageType type) {
        return switch (type) {
            case TEXT -> false;
            default -> true;
        };
    }
}
