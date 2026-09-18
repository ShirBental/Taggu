package com.taggu.app.importer;

import com.taggu.app.config.TagguProperties;
import com.taggu.core.model.SourceType;
import com.taggu.core.source.MessageSourceAdapter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * Imports the synthetic conversations shipped with the project.
 *
 * <p>The mechanism exists so that the system can be demonstrated and developed against without anyone
 * putting a real chat export on disk. Everything it loads is invented; it is off unless asked for.
 */
@Component
public class SampleDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);
    private static final String DEFAULT_DIRECTORY = "sample-data";

    private final ImportService importService;
    private final List<MessageSourceAdapter> adapters;
    private final TagguProperties properties;

    public SampleDataLoader(
            ImportService importService, List<MessageSourceAdapter> adapters, TagguProperties properties) {
        this.importService = importService;
        this.adapters = adapters;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!properties.sampleData().enabled()) {
            return;
        }
        String configured = properties.sampleData().directory();
        Path directory = Path.of(configured == null || configured.isBlank() ? DEFAULT_DIRECTORY : configured);
        if (!Files.isDirectory(directory)) {
            log.warn("Sample data is enabled but {} is not a directory; nothing was loaded", directory);
            return;
        }

        MessageSourceAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.source() == SourceType.WHATSAPP)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no WhatsApp adapter is registered"));

        try (var files = Files.list(directory)) {
            List<Path> exports = files.filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            for (Path export : exports) {
                importOne(adapter, export);
            }
            log.info("Loaded {} sample conversations from {}", exports.size(), directory);
        }
    }

    private void importOne(MessageSourceAdapter adapter, Path export) throws IOException {
        String fileName = export.getFileName().toString();
        String key = fileName.replaceAll("(?i)\\.txt$", "").toLowerCase(Locale.ROOT);
        try (Reader reader = Files.newBufferedReader(export, StandardCharsets.UTF_8)) {
            importService.importExport(
                    adapter,
                    reader,
                    new ImportService.ImportOptions(
                            "sample/" + key, readableTitle(key), properties.defaultZone(), null, fileName, false));
        }
    }

    private static String readableTitle(String key) {
        String spaced = key.replace('-', ' ').replace('_', ' ').trim();
        return spaced.isEmpty() ? key : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
