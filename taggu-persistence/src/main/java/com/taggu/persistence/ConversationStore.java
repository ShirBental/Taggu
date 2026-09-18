package com.taggu.persistence;

import com.taggu.core.model.CanonicalMessage;
import com.taggu.core.model.Conversation;
import com.taggu.core.model.Participant;
import com.taggu.core.source.ParsedConversation;
import com.taggu.persistence.view.ConversationSummary;
import com.taggu.persistence.view.MessageView;
import com.taggu.persistence.view.ParticipantView;
import com.taggu.persistence.view.SearchHit;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores and reads conversations, participants and messages.
 *
 * <p>Writes are idempotent. Canonical ids are derived from source identity, so importing the same
 * export twice inserts nothing the second time and any provenance recorded earlier still points at
 * the right messages.
 */
@Repository
public class ConversationStore {

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Writes a parsed export, skipping anything already stored. */
    @Transactional
    public StoredConversation save(ParsedConversation parsed) {
        Conversation conversation = parsed.conversation();
        upsertConversation(conversation);
        for (Participant participant : conversation.participants()) {
            upsertParticipant(participant);
            linkParticipant(conversation.id(), participant.id());
        }

        Set<String> existing = existingSourceMessageIds(conversation.id());
        List<CanonicalMessage> fresh = parsed.messages().stream()
                .filter(message -> !existing.contains(message.sourceMessageId()))
                .toList();
        insertMessages(fresh);

        return new StoredConversation(conversation.id(), parsed.messages().size(), fresh.size());
    }

    private void upsertConversation(Conversation conversation) {
        Instant startedAt = conversation.startedAt();
        Instant endedAt = conversation.endedAt();

        // Read through getTimestamp rather than as an object: drivers disagree on the Java type they
        // hand back for "timestamp with time zone".
        List<Instant[]> existing = jdbc.query(
                "SELECT started_at, ended_at FROM conversations WHERE id = :id",
                new MapSqlParameterSource("id", conversation.id()),
                (rs, rowNum) ->
                        new Instant[] {instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("ended_at"))});
        if (!existing.isEmpty()) {
            // A second export of the same thread usually overlaps the first, so the stored range only
            // ever widens.
            startedAt = earliest(startedAt, existing.get(0)[0]);
            endedAt = latest(endedAt, existing.get(0)[1]);
        }

        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", conversation.id())
                .addValue("source", conversation.source().name())
                .addValue("sourceConversationId", conversation.sourceConversationId())
                .addValue("title", conversation.title())
                .addValue("startedAt", timestamp(startedAt))
                .addValue("endedAt", timestamp(endedAt))
                .addValue("importedAt", Timestamp.from(Instant.now()));

        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO conversations
                        (id, source, source_conversation_id, title, started_at, ended_at, imported_at)
                    VALUES (:id, :source, :sourceConversationId, :title, :startedAt, :endedAt, :importedAt)
                    """,
                    params);
        } else {
            jdbc.update(
                    """
                    UPDATE conversations
                       SET title = :title, started_at = :startedAt, ended_at = :endedAt, imported_at = :importedAt
                     WHERE id = :id
                    """,
                    params);
        }
    }

    private static Instant earliest(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        return right == null || left.isBefore(right) ? left : right;
    }

    private static Instant latest(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        return right == null || left.isAfter(right) ? left : right;
    }

    private void upsertParticipant(Participant participant) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", participant.id())
                .addValue("source", participant.source().name())
                .addValue("sourceIdentifier", participant.sourceIdentifier())
                .addValue("displayName", participant.displayName());
        int updated = jdbc.update("UPDATE participants SET display_name = :displayName WHERE id = :id", params);
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO participants (id, source, source_identifier, display_name)
                    VALUES (:id, :source, :sourceIdentifier, :displayName)
                    """,
                    params);
        }
    }

    private void linkParticipant(UUID conversationId, UUID participantId) {
        jdbc.update(
                """
                INSERT INTO conversation_participants (conversation_id, participant_id)
                SELECT :conversationId, :participantId
                 WHERE NOT EXISTS (
                       SELECT 1 FROM conversation_participants
                        WHERE conversation_id = :conversationId AND participant_id = :participantId)
                """,
                new MapSqlParameterSource()
                        .addValue("conversationId", conversationId)
                        .addValue("participantId", participantId));
    }

    private Set<String> existingSourceMessageIds(UUID conversationId) {
        return Set.copyOf(jdbc.queryForList(
                "SELECT source_message_id FROM messages WHERE conversation_id = :conversationId",
                new MapSqlParameterSource("conversationId", conversationId),
                String.class));
    }

    private void insertMessages(List<CanonicalMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<SqlParameterSource> rows = new ArrayList<>();
        List<SqlParameterSource> metadataRows = new ArrayList<>();
        for (CanonicalMessage message : messages) {
            rows.add(new MapSqlParameterSource()
                    .addValue("id", message.id())
                    .addValue("conversationId", message.conversationId())
                    .addValue("source", message.source().name())
                    .addValue("sourceMessageId", message.sourceMessageId())
                    .addValue("senderId", message.senderId())
                    .addValue("sentAt", timestamp(message.sentAt()))
                    .addValue("messageType", message.messageType().name())
                    .addValue("body", message.text())
                    .addValue("replyToMessageId", message.replyToMessageId())
                    .addValue("rawContent", message.rawContent()));
            message.metadata()
                    .forEach((key, value) -> metadataRows.add(new MapSqlParameterSource()
                            .addValue("messageId", message.id())
                            .addValue("key", key)
                            .addValue("value", value)));
        }
        jdbc.batchUpdate(
                """
                INSERT INTO messages
                    (id, conversation_id, source, source_message_id, sender_id, sent_at,
                     message_type, body, reply_to_message_id, raw_content)
                VALUES (:id, :conversationId, :source, :sourceMessageId, :senderId, :sentAt,
                        :messageType, :body, :replyToMessageId, :rawContent)
                """,
                rows.toArray(SqlParameterSource[]::new));
        if (!metadataRows.isEmpty()) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO message_metadata (message_id, metadata_key, metadata_value)
                    VALUES (:messageId, :key, :value)
                    """,
                    metadataRows.toArray(SqlParameterSource[]::new));
        }
    }

    // --- reads -------------------------------------------------------------------------------

    public List<ConversationSummary> listConversations() {
        Map<UUID, List<ParticipantView>> participants = participantsByConversation();
        return jdbc.query(
                """
                SELECT c.id, c.source, c.source_conversation_id, c.title, c.started_at, c.ended_at,
                       c.imported_at, (SELECT count(*) FROM messages m WHERE m.conversation_id = c.id) AS message_count
                  FROM conversations c
                 ORDER BY c.started_at DESC NULLS LAST, c.title
                """,
                (rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ConversationSummary(
                            id,
                            rs.getString("source"),
                            rs.getString("source_conversation_id"),
                            rs.getString("title"),
                            instant(rs.getTimestamp("started_at")),
                            instant(rs.getTimestamp("ended_at")),
                            instant(rs.getTimestamp("imported_at")),
                            rs.getLong("message_count"),
                            participants.getOrDefault(id, List.of()));
                });
    }

    public Optional<ConversationSummary> findConversation(UUID conversationId) {
        return listConversations().stream()
                .filter(summary -> summary.id().equals(conversationId))
                .findFirst();
    }

    public List<MessageView> findMessages(UUID conversationId, int limit, int offset) {
        List<MessageView> messages = jdbc.query(
                """
                SELECT m.id, m.conversation_id, m.sender_id, p.display_name, m.sent_at, m.message_type, m.body
                  FROM messages m
                  LEFT JOIN participants p ON p.id = m.sender_id
                 WHERE m.conversation_id = :conversationId
                 ORDER BY m.sent_at, m.source_message_id
                 LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("conversationId", conversationId)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                (rs, rowNum) -> new MessageView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("sender_id", UUID.class),
                        rs.getString("display_name"),
                        instant(rs.getTimestamp("sent_at")),
                        rs.getString("message_type"),
                        rs.getString("body"),
                        List.of()));
        return withTags(messages);
    }

    public long countMessages(UUID conversationId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM messages WHERE conversation_id = :conversationId",
                new MapSqlParameterSource("conversationId", conversationId),
                Long.class);
        return count == null ? 0 : count;
    }

    /**
     * Case-insensitive substring search over message text.
     *
     * <p>Phase 1 deliberately stops here. It is portable, it needs no extension and no extra column,
     * and it is honest about being a substring match rather than pretending to rank. Swapping in
     * PostgreSQL full-text search means changing this one query.
     */
    public List<SearchHit> search(String query, UUID conversationId, int limit) {
        String pattern = "%" + escapeLike(query.toLowerCase(Locale.ROOT)) + "%";
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("pattern", pattern).addValue("limit", limit);
        String scope = "";
        if (conversationId != null) {
            scope = " AND m.conversation_id = :conversationId";
            params.addValue("conversationId", conversationId);
        }
        return jdbc.query(
                """
                SELECT m.id, m.conversation_id, c.title, p.display_name, m.sent_at, m.body
                  FROM messages m
                  JOIN conversations c ON c.id = m.conversation_id
                  LEFT JOIN participants p ON p.id = m.sender_id
                 WHERE lower(m.body) LIKE :pattern ESCAPE '\\'
                """
                        + scope
                        + """
                 ORDER BY m.sent_at DESC
                 LIMIT :limit
                """,
                params,
                (rs, rowNum) -> new SearchHit(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("display_name"),
                        instant(rs.getTimestamp("sent_at")),
                        rs.getString("body")));
    }

    private List<MessageView> withTags(List<MessageView> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        List<UUID> ids = messages.stream().map(MessageView::id).toList();
        Map<UUID, List<String>> tags = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT mt.message_id, t.slug
                  FROM message_tags mt
                  JOIN tags t ON t.id = mt.tag_id
                 WHERE mt.message_id IN (:ids)
                 ORDER BY t.slug
                """,
                new MapSqlParameterSource("ids", ids),
                rs -> {
                    tags.computeIfAbsent(rs.getObject("message_id", UUID.class), key -> new ArrayList<>())
                            .add(rs.getString("slug"));
                });
        return messages.stream()
                .map(message -> new MessageView(
                        message.id(),
                        message.conversationId(),
                        message.senderId(),
                        message.senderName(),
                        message.sentAt(),
                        message.messageType(),
                        message.text(),
                        tags.getOrDefault(message.id(), List.of())))
                .toList();
    }

    private Map<UUID, List<ParticipantView>> participantsByConversation() {
        Map<UUID, List<ParticipantView>> byConversation = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT cp.conversation_id, p.id, p.display_name, p.source_identifier
                  FROM conversation_participants cp
                  JOIN participants p ON p.id = cp.participant_id
                 ORDER BY p.display_name
                """,
                new MapSqlParameterSource(),
                rs -> {
                    byConversation
                            .computeIfAbsent(rs.getObject("conversation_id", UUID.class), key -> new ArrayList<>())
                            .add(new ParticipantView(
                                    rs.getObject("id", UUID.class),
                                    rs.getString("display_name"),
                                    rs.getString("source_identifier")));
                });
        return byConversation;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** What one call to {@link #save} actually wrote. */
    public record StoredConversation(UUID conversationId, int parsedMessages, int newMessages) {}

    /** Participant display names keyed by id, used by callers that render messages. */
    public Map<UUID, String> participantNames(UUID conversationId) {
        return jdbc.query(
                        """
                        SELECT p.id, p.display_name
                          FROM conversation_participants cp
                          JOIN participants p ON p.id = cp.participant_id
                         WHERE cp.conversation_id = :conversationId
                        """,
                        new MapSqlParameterSource("conversationId", conversationId),
                        (rs, rowNum) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("display_name")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
