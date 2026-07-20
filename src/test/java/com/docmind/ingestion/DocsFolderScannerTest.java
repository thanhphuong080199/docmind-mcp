package com.docmind.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;

import com.docmind.domain.DocumentSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class DocsFolderScannerTest {

    @Autowired
    DocsFolderScanner scanner;

    @Autowired
    DocumentSourceRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    @BeforeEach
    void cleanStore() {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE document_source");
    }

    @Test
    void scansFolderIngestingSupportedFilesAndSurvivingFailures() throws Exception {
        Files.writeString(tempDir.resolve("one.md"), "# One\n\nContent about topic one.\n");
        Files.writeString(tempDir.resolve("two.md"), "# Two\n\nContent about topic two.\n");
        Files.writeString(tempDir.resolve("broken.pdf"), "not a pdf");   // must fail, not abort
        Files.writeString(tempDir.resolve("ignored.txt"), "unsupported"); // must be skipped

        DocsFolderScanner.ScanReport report = scanner.scan(tempDir);

        assertThat(report.succeeded()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(3); // 2 INGESTED + 1 FAILED row
    }

    @Test
    void missingFolderYieldsEmptyReport() {
        DocsFolderScanner.ScanReport report = scanner.scan(tempDir.resolve("does-not-exist"));
        assertThat(report.succeeded()).isZero();
        assertThat(report.failed()).isZero();
    }
}
