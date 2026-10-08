package com.taggu.persistence;

import com.taggu.persistence.view.SearchHit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Full-text search over messages and the knowledge extracted from them.
 *
 * <p>PostgreSQL only: it uses the {@code search_vector} columns and indexes that V4 adds, which H2
 * cannot build. The other stores stay portable; this one is tested on PostgreSQL alone.
 *
 * <p>The query is read by {@code websearch_to_tsquery}: words are all required, in any order;
 * {@code "quoted words"} must appear together; {@code or} gives alternatives; a leading {@code -}
 * excludes. It never raises a syntax error, whatever is typed. Matching is on whole words, case
 * insensitive, with the {@code simple} configuration: no stemming and no stop words, so it behaves the
 * same for every language at the cost of not matching "quote" to "quoted".
 *
 * <p>This class is the one place search is implemented. A different backend, or a semantic ranking
 * added beside this one, belongs behind the same method.
 */
@Repository
public class SearchStore {

    private static final String MESSAGES =
            """
            SELECT 'MESSAGE' AS kind, m.id AS item_id, m.id AS message_id, m.conversation_id, c.title,
                   p.display_name, m.sent_at, m.body AS text, ts_rank(m.search_vector, q.tsq) AS rank,
                   ARRAY[m.id] AS source_ids
              FROM messages m
             CROSS JOIN q
              JOIN conversations c ON c.id = m.conversation_id
              LEFT JOIN participants p ON p.id = m.sender_id
             WHERE m.search_vector @@ q.tsq
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SearchStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param conversationId restricts the search to one conversation, or {@code null} for all of them
     * @return the best matches first; for equal rank, the most recent first
     */
    public List<SearchHit> search(String query, UUID conversationId, int limit) {
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("query", query).addValue("limit", limit);
        String messageScope = "";
        String knowledgeScope = "";
        if (conversationId != null) {
            messageScope = " AND m.conversation_id = :conversationId\n";
            knowledgeScope = " AND k.conversation_id = :conversationId\n";
            params.addValue("conversationId", conversationId);
        }
        String sql = "WITH q AS (SELECT websearch_to_tsquery('simple', :query) AS tsq)\n"
                + MESSAGES
                + messageScope
                + knowledge("FACT", "facts", "statement", "fact_sources", "fact_id", knowledgeScope)
                + knowledge("EVENT", "events", "title", "event_sources", "event_id", knowledgeScope)
                + knowledge(
                        "ACTION_ITEM", "action_items", "description", "action_item_sources", "action_item_id", knowledgeScope)
                + knowledge("DECISION", "decisions", "statement", "decision_sources", "decision_id", knowledgeScope)
                + " ORDER BY rank DESC, sent_at DESC, item_id\n LIMIT :limit";
        return jdbc.query(
                sql,
                params,
                (rs, rowNum) -> new SearchHit(
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getString("title"),
                        rs.getString("display_name"),
                        ConversationStore.instant(rs.getTimestamp("sent_at")),
                        rs.getString("text"),
                        rs.getString("kind"),
                        rs.getObject("item_id", UUID.class),
                        rs.getDouble("rank"),
                        List.of((UUID[]) rs.getArray("source_ids").getArray())));
    }

    /**
     * One knowledge table's branch of the search. The lateral join brings each row's evidence with it:
     * every cited message id, oldest first, and when the earliest of them was sent.
     */
    private static String knowledge(
            String kind, String table, String textColumn, String sourceTable, String ownerColumn, String scope) {
        return """
                UNION ALL
                SELECT '%s', k.id, src.ids[1], k.conversation_id, c.title, NULL, src.first_at, k.%s,
                       ts_rank(k.search_vector, q.tsq), src.ids
                  FROM %s k
                 CROSS JOIN q
                  JOIN conversations c ON c.id = k.conversation_id
                  JOIN LATERAL (
                           SELECT array_agg(s.message_id ORDER BY m.sent_at, m.source_order, m.source_message_id) AS ids,
                                  min(m.sent_at) AS first_at
                             FROM %s s
                             JOIN messages m ON m.id = s.message_id
                            WHERE s.%s = k.id) src ON true
                 WHERE k.search_vector @@ q.tsq
                """
                        .formatted(kind, textColumn, table, sourceTable, ownerColumn)
                + scope;
    }
}
