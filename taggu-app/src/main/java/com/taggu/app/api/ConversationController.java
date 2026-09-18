package com.taggu.app.api;

import com.taggu.persistence.ConversationStore;
import com.taggu.persistence.KnowledgeStore;
import com.taggu.persistence.view.ConversationSummary;
import com.taggu.persistence.view.KnowledgeItemViews.ConversationKnowledge;
import com.taggu.persistence.view.MessageView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reads imported conversations and the knowledge extracted from them. */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final int MAX_PAGE_SIZE = 500;

    private final ConversationStore conversationStore;
    private final KnowledgeStore knowledgeStore;

    public ConversationController(ConversationStore conversationStore, KnowledgeStore knowledgeStore) {
        this.conversationStore = conversationStore;
        this.knowledgeStore = knowledgeStore;
    }

    @GetMapping
    public List<ConversationSummary> list() {
        return conversationStore.listConversations();
    }

    @GetMapping("/{id}")
    public ConversationSummary get(@PathVariable UUID id) {
        return conversationStore.findConversation(id).orElseThrow(() -> new NotFoundException("conversation", id));
    }

    @GetMapping("/{id}/messages")
    public MessagePage messages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        conversationStore.findConversation(id).orElseThrow(() -> new NotFoundException("conversation", id));
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        int start = Math.max(offset, 0);
        List<MessageView> messages = conversationStore.findMessages(id, pageSize, start);
        return new MessagePage(id, start, pageSize, conversationStore.countMessages(id), messages);
    }

    @GetMapping("/{id}/knowledge")
    public ConversationKnowledge knowledge(@PathVariable UUID id) {
        conversationStore.findConversation(id).orElseThrow(() -> new NotFoundException("conversation", id));
        return knowledgeStore.findByConversation(id);
    }

    /** One page of messages, with enough to ask for the next. */
    public record MessagePage(UUID conversationId, int offset, int limit, long total, List<MessageView> messages) {}
}
