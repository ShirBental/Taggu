package com.taggu.ingest.whatsapp;

import com.taggu.core.source.ImportRequest;
import com.taggu.core.source.ParsedConversation;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;

/** Loads the synthetic export fixtures. Every one of them is invented; none is a real conversation. */
final class WhatsAppFixtures {

    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private WhatsAppFixtures() {}

    static ParsedConversation parse(String fixture) throws IOException {
        return parse(fixture, null);
    }

    static ParsedConversation parse(String fixture, String localeHint) throws IOException {
        try (Reader reader = open(fixture)) {
            return new WhatsAppTextExportAdapter()
                    .parse(reader, new ImportRequest(fixture, "Fixture " + fixture, ZONE, localeHint));
        }
    }

    private static Reader open(String fixture) {
        InputStream stream = WhatsAppFixtures.class.getResourceAsStream("/fixtures/" + fixture);
        if (stream == null) {
            throw new IllegalArgumentException("missing fixture: " + fixture);
        }
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}
