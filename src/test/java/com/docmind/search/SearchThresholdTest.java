package com.docmind.search;

import java.nio.file.Files;
import java.nio.file.Path;

import com.docmind.config.DocmindProperties;
import com.docmind.ingestion.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class SearchThresholdTest {

    @Autowired
    VectorStore vectorStore;

    @Autowired
    IngestionService ingestionService;

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
    void thresholdFiltersLowScoringChunks() throws Exception {
        Path file = tempDir.resolve("kafka.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        ingestionService.ingestFile(file);

        SearchService permissive = new SearchService(vectorStore,
                new DocmindProperties(null, false, 0.0, null), jdbcTemplate);
        SearchService strict = new SearchService(vectorStore,
                new DocmindProperties(null, false, 0.99, null), jdbcTemplate);

        assertThat(permissive.search("event streaming", 5)).isNotEmpty();
        assertThat(strict.search("event streaming", 5)).isEmpty();
    }
}
