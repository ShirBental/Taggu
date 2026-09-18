package com.taggu.app.config;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about an import that the exported file cannot tell us.
 *
 * @param defaultZone the zone to read wall-clock timestamps in when a request does not say. Chat
 *     exports have no offset, so this has to be a decision rather than an assumption.
 * @param extraction how knowledge extraction is run after an import
 * @param sampleData the synthetic conversations loaded on startup when asked for
 */
@ConfigurationProperties(prefix = "taggu")
public record TagguProperties(ZoneId defaultZone, Extraction extraction, SampleData sampleData) {

    public TagguProperties {
        defaultZone = defaultZone == null ? ZoneId.systemDefault() : defaultZone;
        extraction = extraction == null ? new Extraction(true, 0, 0) : extraction;
        sampleData = sampleData == null ? new SampleData(false, null) : sampleData;
    }

    /**
     * @param enabled whether an import is followed by extraction
     * @param focusWindowSize how many messages one extraction window is responsible for
     * @param leadInSize how many earlier messages an extractor may read for context
     */
    public record Extraction(boolean enabled, int focusWindowSize, int leadInSize) {
        public Extraction {
            focusWindowSize = focusWindowSize > 0 ? focusWindowSize : 8;
            leadInSize = leadInSize >= 0 ? leadInSize : 4;
        }
    }

    /**
     * @param enabled whether the synthetic conversations are imported on startup
     * @param directory where they live; the classpath copy is used when this is not set
     */
    public record SampleData(boolean enabled, String directory) {}
}
