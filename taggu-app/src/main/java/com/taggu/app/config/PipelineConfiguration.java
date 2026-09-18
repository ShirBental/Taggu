package com.taggu.app.config;

import com.taggu.core.extraction.KnowledgeExtractor;
import com.taggu.core.source.MessageSourceAdapter;
import com.taggu.extraction.rule.RuleBasedKnowledgeExtractor;
import com.taggu.ingest.whatsapp.WhatsAppTextExportAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the pipeline from interfaces, not implementations.
 *
 * <p>Adding Gmail means adding a {@link MessageSourceAdapter} bean. Adding a model-backed extractor
 * means replacing the {@link KnowledgeExtractor} bean. Neither touches anything else.
 */
@Configuration
public class PipelineConfiguration {

    @Bean
    public MessageSourceAdapter whatsAppTextExportAdapter() {
        return new WhatsAppTextExportAdapter();
    }

    @Bean
    public KnowledgeExtractor knowledgeExtractor() {
        return new RuleBasedKnowledgeExtractor();
    }
}
