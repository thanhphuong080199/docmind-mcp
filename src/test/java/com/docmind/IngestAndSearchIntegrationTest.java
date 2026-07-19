package com.docmind;

import java.util.List;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionService;
import com.docmind.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class IngestAndSearchIntegrationTest {

    @Autowired
    IngestionService ingestionService;

    @Autowired
    SearchService searchService;

    @Test
    void ingestThenSearchReturnsRelevantChunk() {
        DocumentSource doc = ingestionService.ingestMarkdown(
                new ClassPathResource("samples/spring-boot-overview.md"),
                "Spring Boot Overview");

        assertThat(doc.chunkCount()).isGreaterThan(0);
        assertThat(doc.status()).isEqualTo("INGESTED");

        List<SearchService.SearchResult> results =
                searchService.search("How does auto-configuration work?", 3);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().docId()).isEqualTo(doc.id().toString());
        assertThat(results.getFirst().content().toLowerCase()).contains("auto-configur");
    }

    @Test
    void reingestingSameSourceReplacesInsteadOfDuplicating() {
        var resource = new ClassPathResource("samples/spring-boot-overview.md");
        ingestionService.ingestMarkdown(resource, "Spring Boot Overview");
        DocumentSource second = ingestionService.ingestMarkdown(resource, "Spring Boot Overview");

        List<SearchService.SearchResult> results = searchService.search("starters", 10);
        long distinctDocIds = results.stream().map(SearchService.SearchResult::docId).distinct().count();

        assertThat(distinctDocIds).isEqualTo(1);
        assertThat(results.getFirst().docId()).isEqualTo(second.id().toString());
    }
}
