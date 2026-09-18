package com.taggu.app.api;

import com.taggu.persistence.ConversationStore;
import com.taggu.persistence.view.SearchHit;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Searches message text.
 *
 * <p>Phase 1 is a case-insensitive substring match. It is the smallest thing that makes imported
 * conversations useful, and replacing it with PostgreSQL full-text search changes one query.
 */
@RestController
@Validated
public class SearchController {

    private static final int MAX_RESULTS = 200;

    private final ConversationStore conversationStore;

    public SearchController(ConversationStore conversationStore) {
        this.conversationStore = conversationStore;
    }

    @GetMapping("/api/search")
    public SearchResponse search(
            @RequestParam("q") @NotBlank String query,
            @RequestParam(value = "conversationId", required = false) UUID conversationId,
            @RequestParam(defaultValue = "50") int limit) {
        List<SearchHit> hits = conversationStore.search(query.trim(), conversationId, Math.clamp(limit, 1, MAX_RESULTS));
        return new SearchResponse(query, hits.size(), hits);
    }

    /** Search results, with the query echoed so a caller can tell responses apart. */
    public record SearchResponse(String query, int count, List<SearchHit> results) {}
}
