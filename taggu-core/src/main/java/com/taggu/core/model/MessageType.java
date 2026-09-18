package com.taggu.core.model;

/** What a canonical message carries, independent of the source that produced it. */
public enum MessageType {
    /** Ordinary text written by a participant. */
    TEXT,
    /** Text was replaced by a media placeholder (image, video, audio, sticker). */
    MEDIA,
    /** A file or document was shared. */
    ATTACHMENT,
    /** A shared location. */
    LOCATION,
    /** A shared contact card. */
    CONTACT,
    /** Written by the platform, not by a participant (joins, leaves, encryption notices). */
    SYSTEM,
    /** The participant deleted the message; only the tombstone survives in the export. */
    DELETED,
    /** Recognised as a message but the kind could not be determined. */
    UNKNOWN
}
