package com.taggu.app.api;

import com.taggu.app.importer.ImportService;
import com.taggu.app.importer.ImportService.ImportOptions;
import com.taggu.app.importer.ImportSummary;
import com.taggu.core.model.SourceType;
import com.taggu.core.source.MessageSourceAdapter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Accepts an export file and runs it through the pipeline. */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private final ImportService importService;
    private final List<MessageSourceAdapter> adapters;

    public ImportController(ImportService importService, List<MessageSourceAdapter> adapters) {
        this.importService = importService;
        this.adapters = adapters;
    }

    /**
     * Imports a WhatsApp {@code .txt} export.
     *
     * @param zone the zone the export's timestamps should be read in; the configured default is used
     *     when it is left out
     * @param localeHint a hint such as {@code en-US}, used only when the file's own dates cannot settle
     *     whether they are day-first or month-first
     * @param includeRawLines echo unreadable lines back in the response. Off by default: those lines
     *     are message content.
     */
    @PostMapping(path = "/whatsapp", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportSummary importWhatsApp(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "zone", required = false) String zone,
            @RequestParam(value = "localeHint", required = false) String localeHint,
            @RequestParam(value = "includeRawLines", defaultValue = "false") boolean includeRawLines)
            throws IOException {

        String fileName = file.getOriginalFilename() == null ? "whatsapp-export.txt" : file.getOriginalFilename();
        String key = conversationId == null || conversationId.isBlank() ? conversationKey(fileName) : conversationId;

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return importService.importExport(
                    adapterFor(SourceType.WHATSAPP),
                    reader,
                    new ImportOptions(
                            key,
                            title == null || title.isBlank() ? key : title,
                            zone == null || zone.isBlank() ? null : ZoneId.of(zone),
                            localeHint,
                            fileName,
                            includeRawLines));
        }
    }

    private MessageSourceAdapter adapterFor(SourceType source) {
        return adapters.stream()
                .filter(adapter -> adapter.source() == source)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no adapter is registered for " + source));
    }

    /** Turns an export file name into a stable conversation key, so re-importing it updates the thread. */
    private static String conversationKey(String fileName) {
        String base = fileName.replaceAll("(?i)\\.txt$", "").trim();
        String slug = base.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("(^-|-$)", "").toLowerCase(Locale.ROOT);
        return slug.isBlank() ? "whatsapp-export" : slug;
    }
}
