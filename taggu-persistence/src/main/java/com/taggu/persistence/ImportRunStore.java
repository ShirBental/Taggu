package com.taggu.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Records that an import happened, and how it went.
 *
 * <p>Counts and a file name only. This is the table an operator is most likely to read, so nothing a
 * participant wrote is kept in it.
 */
@Repository
public class ImportRunStore {

    private final NamedParameterJdbcTemplate jdbc;

    public ImportRunStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID start(String source, String sourceReference) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO import_runs (id, source, source_reference, status, started_at)
                VALUES (:id, :source, :sourceReference, 'RUNNING', :startedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("source", source)
                        .addValue("sourceReference", sourceReference)
                        .addValue("startedAt", Timestamp.from(Instant.now())));
        return id;
    }

    public void succeed(
            UUID runId, UUID conversationId, int parsedMessages, int newMessages, int parseIssues, int knowledgeItems) {
        jdbc.update(
                """
                UPDATE import_runs
                   SET status = 'SUCCEEDED', conversation_id = :conversationId, finished_at = :finishedAt,
                       parsed_messages = :parsedMessages, new_messages = :newMessages,
                       parse_issues = :parseIssues, knowledge_items = :knowledgeItems
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", runId)
                        .addValue("conversationId", conversationId)
                        .addValue("finishedAt", Timestamp.from(Instant.now()))
                        .addValue("parsedMessages", parsedMessages)
                        .addValue("newMessages", newMessages)
                        .addValue("parseIssues", parseIssues)
                        .addValue("knowledgeItems", knowledgeItems));
    }

    public void fail(UUID runId) {
        jdbc.update(
                "UPDATE import_runs SET status = 'FAILED', finished_at = :finishedAt WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", runId)
                        .addValue("finishedAt", Timestamp.from(Instant.now())));
    }
}
