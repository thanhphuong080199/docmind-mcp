package com.docmind.ingestion;

import com.docmind.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class IngestionServiceConfluenceTest {

    private static final String URI = "https://c.atlassian.net/wiki/spaces/DOCS/pages/456";

    @Autowired
    IngestionService ingestionService;

    @Autowired
    SearchService searchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanStore() {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE document_source");
    }

    @Test
    void ingestsConfluencePageWithCorrectTypeAndIsSearchable() {
        IngestionService.IngestOutcome outcome = ingestionService.ingestConfluencePage(
                URI, "Deploy Guide",
                "<h1>Deploy Guide</h1><p>Run kubectl apply to deploy the service.</p>");

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.document().docType()).isEqualTo("CONFLUENCE");
        assertThat(outcome.document().chunkCount()).isGreaterThan(0);

        var results = searchService.search("how do I deploy the service?", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().docId()).isEqualTo(outcome.document().id().toString());
    }

    @Test
    void reingestingUnchangedPageIsSkipped() {
        String body = "<p>unchanged content</p>";
        ingestionService.ingestConfluencePage(URI, "Title", body);

        IngestionService.IngestOutcome second = ingestionService.ingestConfluencePage(URI, "Title", body);

        assertThat(second.skipped()).isTrue();
    }

    @Test
    void renameChangesChecksumAndReingests() {
        String body = "<p>same body</p>";
        ingestionService.ingestConfluencePage(URI, "Old Title", body);

        IngestionService.IngestOutcome renamed = ingestionService.ingestConfluencePage(URI, "New Title", body);

        assertThat(renamed.skipped()).isFalse();
    }
}
