package com.docmind.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;

import com.docmind.domain.DocumentSource;
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
class IngestionServiceFileTest {

    @Autowired
    IngestionService ingestionService;

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
    void ingestsMarkdownFileFromFilesystem() throws Exception {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");

        DocumentSource doc = ingestionService.ingestFile(file);

        assertThat(doc.title()).isEqualTo("notes");
        assertThat(doc.docType()).isEqualTo("MARKDOWN");
        assertThat(doc.status()).isEqualTo("INGESTED");
        assertThat(doc.chunkCount()).isGreaterThan(0);
        assertThat(doc.sourceUri()).startsWith("file:");
    }

    @Test
    void unchangedFileIsSkippedOnReingest() throws Exception {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");

        DocumentSource first = ingestionService.ingestFile(file);
        DocumentSource second = ingestionService.ingestFile(file);

        assertThat(second.id()).isEqualTo(first.id()); // re-ingest would mint a new id
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void changedFileIsReingestedWithNewChunks() throws Exception {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        DocumentSource first = ingestionService.ingestFile(file);

        Files.writeString(file, "# Kafka\n\nKafka now also does queues (KIP-932).\n");
        DocumentSource second = ingestionService.ingestFile(file);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(repository.count()).isEqualTo(1);
        Integer chunkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'doc_id' = ?",
                Integer.class, second.id().toString());
        assertThat(chunkRows).isEqualTo(second.chunkCount());
    }

    @Test
    void ingestsPdfFile() throws Exception {
        Path file = tempDir.resolve("vectors.pdf");
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Vector databases store embeddings for semantic similarity search.");
                cs.endText();
            }
            pdf.save(file.toFile());
        }

        DocumentSource doc = ingestionService.ingestFile(file);

        assertThat(doc.docType()).isEqualTo("PDF");
        assertThat(doc.status()).isEqualTo("INGESTED");
        assertThat(doc.chunkCount()).isGreaterThan(0);
    }

    @Test
    void unreadablePdfIsMarkedFailed() throws Exception {
        Path file = tempDir.resolve("broken.pdf");
        Files.writeString(file, "this is not a pdf");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestionService.ingestFile(file))
                .isInstanceOf(IngestionException.class);

        DocumentSource failed = repository.findBySourceUri(
                file.toAbsolutePath().normalize().toUri().toString()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.chunkCount()).isZero();
    }
}
