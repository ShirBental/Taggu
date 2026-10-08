package com.taggu.app;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs every {@link ImportApiTest} case again on PostgreSQL with all migrations applied: the whole
 * pipeline, through HTTP, on the database it ships on. Search is tested here only, because it is
 * PostgreSQL full-text search and H2 cannot run it.
 */
@RequiresPostgres
class ImportApiPostgresTest extends ImportApiTest {

    @Autowired
    private DataSource postgres;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        // The H2 configuration builds the schema from V1 alone at startup; here Flyway owns it.
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @BeforeEach
    @Override
    void resetSchema() {
        PostgresTestDatabase.rebuild(postgres);
    }

    @Test
    @DisplayName("search finds the messages and the extracted knowledge of an imported export")
    void searchesMessagesAndKnowledge() throws Exception {
        mockMvc.perform(multipart("/api/import/whatsapp").file(export())).andExpect(status().isOk());

        // The message and the fact extracted from it both mention the quote; each says what it is.
        mockMvc.perform(get("/api/search").param("q", "caterer quoted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("caterer quoted"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.results[*].kind", containsInAnyOrder("MESSAGE", "FACT")))
                .andExpect(jsonPath("$.results[?(@.kind == 'MESSAGE')].text")
                        .value("The caterer quoted $220 for the dessert table"))
                .andExpect(jsonPath("$.results[?(@.kind == 'MESSAGE')].senderName").value("Mom"))
                .andExpect(jsonPath("$.results[0].messageId").exists())
                .andExpect(jsonPath("$.results[0].sourceMessageIds.length()").value(1));

        mockMvc.perform(get("/api/search").param("q", "\"go with them\""))
                .andExpect(jsonPath("$.results[*].kind", containsInAnyOrder("MESSAGE", "DECISION")));

        mockMvc.perform(get("/api/search").param("q", "caterer").param("limit", "1"))
                .andExpect(jsonPath("$.count").value(1));
        mockMvc.perform(get("/api/search").param("q", "'; DROP TABLE messages; --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }
}
