package com.taggu.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Walks the whole pipeline the way a caller does: upload an export, then read back conversations,
 * messages, search results and knowledge.
 *
 * <p>The export below is invented, as all test data in this project is.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportApiTest {

    private static final String EXPORT =
            """
            14/03/2024, 18:02 - Mom: Want to come Sunday?
            14/03/2024, 18:03 - Yael: Sure
            14/03/2024, 18:03 - Mom: Around 4?
            14/03/2024, 18:04 - Yael: Perfect
            14/03/2024, 18:05 - Mom: The caterer quoted $220 for the dessert table
            14/03/2024, 18:06 - Yael: Let's go with them
            14/03/2024, 18:07 - Yael: I'll call them tomorrow
            99/99/2024, 18:09 - Mom: Impossible date
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    private final ObjectMapper json = new ObjectMapper();

    /** Each test starts from an empty database built by the project's own V1 migration. */
    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("sql/reset.sql")));
            ScriptUtils.executeSqlScript(
                    connection, new EncodedResource(new ClassPathResource("db/migration/V1__initial_schema.sql")));
        }
    }

    @Test
    @DisplayName("an uploaded export becomes searchable conversations and knowledge with provenance")
    void importsAnExportEndToEnd() throws Exception {
        String importResponse = mockMvc.perform(multipart("/api/import/whatsapp").file(export()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("WHATSAPP"))
                .andExpect(jsonPath("$.parsedMessages").value(7))
                .andExpect(jsonPath("$.newMessages").value(7))
                .andExpect(jsonPath("$.parseIssueCount").value(1))
                // The unreadable line is reported, but its content is withheld unless asked for.
                .andExpect(jsonPath("$.issues[0].rawLine").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String conversationId = json.readTree(importResponse).get("conversationId").asText();

        mockMvc.perform(get("/api/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messageCount").value(7))
                .andExpect(jsonPath("$[0].participants.length()").value(2));

        mockMvc.perform(get("/api/conversations/{id}/messages", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.messages[0].text").value("Want to come Sunday?"))
                .andExpect(jsonPath("$.messages[0].senderName").value("Mom"));

        mockMvc.perform(get("/api/search").param("q", "quoted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.results[0].text").value(
                        "The caterer quoted $220 for the dessert table"));

        String knowledge = mockMvc.perform(get("/api/conversations/{id}/knowledge", conversationId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode node = json.readTree(knowledge);

        assertThat(node.get("events")).hasSize(1);
        assertThat(node.get("events").get(0).get("startsAt").asText()).startsWith("2024-03-17T15:00");
        assertThat(node.get("facts").get(0).get("statement").asText()).contains("$220");
        assertThat(node.get("decisions").get(0).get("statement").asText()).isEqualTo("Let's go with them");
        assertThat(node.get("actions").get(0).get("assignee").asText()).isEqualTo("Yael");

        // Nothing is returned without the messages that back it.
        for (String kind : new String[] {"facts", "events", "actions", "decisions"}) {
            assertThat(node.get(kind)).isNotEmpty();
            node.get(kind).forEach(item -> assertThat(item.get("sourceMessageIds")).isNotEmpty());
        }
    }

    @Test
    @DisplayName("uploading the same export twice stores nothing new")
    void reimportIsIdempotent() throws Exception {
        mockMvc.perform(multipart("/api/import/whatsapp").file(export()).param("conversationId", "twice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newMessages").value(7));

        mockMvc.perform(multipart("/api/import/whatsapp").file(export()).param("conversationId", "twice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parsedMessages").value(7))
                .andExpect(jsonPath("$.newMessages").value(0));
    }

    @Test
    @DisplayName("unreadable lines are echoed back only when the caller asks")
    void echoesRawLinesOnlyOnRequest() throws Exception {
        mockMvc.perform(multipart("/api/import/whatsapp")
                        .file(export())
                        .param("conversationId", "raw")
                        .param("includeRawLines", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[0].rawLine").value("99/99/2024, 18:09 - Mom: Impossible date"));
    }

    @Test
    @DisplayName("a missing conversation is a 404, not a stack trace")
    void unknownConversationIsNotFound() throws Exception {
        mockMvc.perform(get("/api/conversations/{id}/messages", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    private static MockMultipartFile export() {
        return new MockMultipartFile(
                "file", "sunday-dinner.txt", "text/plain", EXPORT.getBytes(StandardCharsets.UTF_8));
    }
}
