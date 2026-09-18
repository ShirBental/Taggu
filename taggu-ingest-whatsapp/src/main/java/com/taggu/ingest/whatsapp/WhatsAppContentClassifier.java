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

    private WhatsAppContentClassifier() {}

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
