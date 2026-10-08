package com.taggu.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taggu.core.extraction.ExtractionContext;
import com.taggu.core.extraction.KnowledgeExtractor;
import com.taggu.core.knowledge.ExtractionResult;
import com.taggu.core.knowledge.Fact;
import com.taggu.core.knowledge.Provenance;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The pipeline with an extractor that cannot be trusted, which is what a model-backed one will be.
 *
 * <p>The extractor here returns one fact citing a real message and one citing a message that does not
 * exist. Only the first may reach the database, and the import must still succeed.
 */
// Replacing the extractor gives this class its own application context, so it gets its own in-memory
// database too: sharing one with ImportApiTest would run the schema script against it a second time.
@SpringBootTest(
        properties = "spring.datasource.url=jdbc:h2:mem:taggu-app-validation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class ExtractionValidationApiTest {

    private static final String EXPORT =
            """
            14/03/2024, 18:05 - Mom: The caterer quoted $220 for the dessert table
            14/03/2024, 18:06 - Yael: Let's go with them
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    private KnowledgeExtractor extractor;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void resetSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("sql/reset.sql")));
            ScriptUtils.executeSqlScript(
                    connection, new EncodedResource(new ClassPathResource("db/migration/V1__initial_schema.sql")));
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource("db/migration/V5__message_source_order.sql")));
        }
    }

    @Test
    @DisplayName("knowledge citing a message the extractor was never shown is dropped, not stored")
    void dropsUngroundedKnowledgeBeforeItIsStored() throws Exception {
        when(extractor.name()).thenReturn("misbehaving");
        when(extractor.extract(any())).thenAnswer(invocation -> {
            ExtractionContext window = invocation.getArgument(0);
            UUID real = window.focus().get(0).id();
            return new ExtractionResult(
                    "misbehaving",
                    List.of(),
                    List.of(),
                    List.of(
                            new Fact("The caterer quoted $220", null, 0.9, Provenance.ofMessages(real)),
                            new Fact("The caterer is fully booked", null, 0.9, Provenance.ofMessages(UUID.randomUUID()))),
                    List.of(),
                    List.of(),
                    List.of());
        });

        String response = mockMvc.perform(multipart("/api/import/whatsapp")
                        .file(new MockMultipartFile(
                                "file", "caterer.txt", "text/plain", EXPORT.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newMessages").value(2))
                .andExpect(jsonPath("$.knowledgeItems").value(1))
                .andExpect(jsonPath("$.rejectedKnowledgeItems").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String conversationId = json.readTree(response).get("conversationId").asText();

        JsonNode knowledge = json.readTree(mockMvc.perform(get("/api/conversations/{id}/knowledge", conversationId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(knowledge.get("facts")).hasSize(1);
        assertThat(knowledge.get("facts").get(0).get("statement").asText()).isEqualTo("The caterer quoted $220");
    }
}
