package com.docmind.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DocsFolderScanner {

    private static final Logger log = LoggerFactory.getLogger(DocsFolderScanner.class);

    private final IngestionService ingestionService;

    public DocsFolderScanner(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public ScanReport scan(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.info("Docs folder {} does not exist, nothing to scan", dir);
            return new ScanReport(0, 0);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(DocsFolderScanner::isSupported).sorted().toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + dir, e);
        }

        int succeeded = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                ingestionService.ingestFile(file);
                succeeded++;
            }
            catch (RuntimeException e) {
                log.warn("Skipping {}: {}", file, e.getMessage());
                failed++;
            }
        }
        log.info("Scanned {}: {} ingested/unchanged, {} failed", dir, succeeded, failed);
        return new ScanReport(succeeded, failed);
    }

    private static boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return Files.isRegularFile(path)
                && (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".pdf"));
    }

    public record ScanReport(int succeeded, int failed) {
    }
}
