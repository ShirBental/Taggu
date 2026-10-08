package com.taggu.app.api;

import com.taggu.persistence.SearchStore;
import com.taggu.persistence.view.SearchHit;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Searches messages and the knowledge extracted from them.
 *
 * <p>{@code q} is read the way a web search box is: every word must match, in any order; "quoted
 * words" must appear together; {@code or} gives alternatives; a leading {@code -} excludes. Results
 * are whole-word matches, best first. See {@link SearchStore}.
 */
@RestController
@Validated
public class SearchController {

    private static final int MAX_RESULTS = 200;

    private final SearchStore searchStore;

    public SearchController(SearchStore searchStore) {
        this.searchStore = searchStore;
    }

    @GetMapping("/api/search")
    public SearchResponse search(
            @RequestParam("q") @NotBlank String query,
            @RequestParam(value = "conversationId", required = false) UUID conversationId,
            @RequestParam(defaultValue = "50") int limit) {
        List<SearchHit> hits = searchStore.search(query.trim(), conversationId, Math.clamp(limit, 1, MAX_RESULTS));
        return new SearchResponse(query, hits.size(), hits);
    }

    /** Search results, with the query echoed so a caller can tell responses apart. */
    public record SearchResponse(String query, int count, List<SearchHit> results) {}
}
