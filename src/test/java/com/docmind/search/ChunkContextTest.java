package com.docmind.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionService;
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
class ChunkContextTest {

    @Autowired
    SearchService searchService;

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
    void returnsNeighboringChunksInOrder() throws Exception {
        // Long repetitive sections force the splitter to emit multiple chunks
        StringBuilder md = new StringBuilder();
        for (int section = 1; section <= 6; section++) {
            md.append("# Section ").append(section).append("\n\n");
            md.append(("Topic " + section + " sentence. ").repeat(400)).append("\n\n");
        }
        Path file = tempDir.resolve("long.md");
        Files.writeString(file, md.toString());
        DocumentSource doc = ingestionService.ingestFile(file);
        assertThat(doc.chunkCount()).isGreaterThanOrEqualTo(3);

        List<SearchService.ChunkContext> context =
                searchService.chunkContext(doc.id(), 1, 1);

        assertThat(context).extracting(SearchService.ChunkContext::chunkIndex)
                .containsExactly(0, 1, 2);
        assertThat(searchService.chunkContext(UUID.randomUUID(), 1, 1)).isEmpty();
    }

    @Test
    void searchResultsCarryChunkIndex() throws Exception {
        Path file = tempDir.resolve("kafka.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        ingestionService.ingestFile(file);

        assertThat(searchService.search("event streaming", 3).getFirst().chunkIndex())
                .isGreaterThanOrEqualTo(0);
    }
}
