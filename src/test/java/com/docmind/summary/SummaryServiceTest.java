package com.docmind.summary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import com.docmind.ingestion.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class SummaryServiceTest {

    @TestConfiguration
    static class StubSummarizerConfig {

        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        @Primary
        LlmSummarizer stubSummarizer() {
            return content -> "Stub summary (call " + CALLS.incrementAndGet() + ")";
        }
    }

    @Autowired
    SummaryService summaryService;

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
        StubSummarizerConfig.CALLS.set(0);
    }

    @Test
    void generatesOncePersistsAndCaches() throws Exception {
        Path file = tempDir.resolve("kafka.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        DocumentSource doc = ingestionService.ingestFile(file);

        String first = summaryService.getSummary(doc.id());
        String second = summaryService.getSummary(doc.id());

        assertThat(first).isEqualTo("Stub summary (call 1)");
        assertThat(second).isEqualTo(first);
        assertThat(StubSummarizerConfig.CALLS.get()).isEqualTo(1);
        assertThat(repository.findById(doc.id()).orElseThrow().summary()).isEqualTo(first);
    }

    @Test
    void unknownDocIdThrows() {
        assertThatThrownBy(() -> summaryService.getSummary(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No document with id");
    }
}
