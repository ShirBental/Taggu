package com.taggu.persistence.view;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One search result: a message, or a piece of knowledge extracted from messages.
 *
 * <p>The first six fields are what a result has always carried, and they are filled in for every kind
 * of hit so a caller written for message-only search keeps working: for knowledge, {@code messageId}
 * and {@code sentAt} are those of its earliest evidence, {@code text} is the statement, and
 * {@code senderName} is {@code null}, since knowledge can rest on what several people said.
 *
 * @param kind MESSAGE, FACT, EVENT, ACTION_ITEM or DECISION
 * @param itemId the id of the message or knowledge row that matched
 * @param rank how well it matched; only comparable within one response
 * @param sourceMessageIds the message itself, or the evidence behind a piece of knowledge, oldest first
 */
public record SearchHit(
        UUID messageId,
        UUID conversationId,
        String conversationTitle,
        String senderName,
        Instant sentAt,
        String text,
        String kind,
        UUID itemId,
        double rank,
        List<UUID> sourceMessageIds) {}
