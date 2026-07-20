package com.docmind.confluence;

import java.util.List;

import com.docmind.ingestion.IngestionService;
import com.docmind.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest(properties = {
        "docmind.confluence.base-url=https://c.atlassian.net",
        "docmind.confluence.email=me@c.com",
        "docmind.confluence.api-token=tok",
        "docmind.confluence.space-keys=DOCS"
})
class ConfluenceSyncServiceTest {

    @MockitoBean
    ConfluenceClient client;

    @Autowired
    ConfluenceSyncService syncService;

    @Autowired
    IngestionService ingestionService;

    @Autowired
    SearchService searchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private ConfluencePage page(String id, String title, String body) {
        return new ConfluencePage(id, "DOCS", title, body,
                "https://c.atlassian.net/wiki/spaces/DOCS/pages/" + id + "/" + title);
    }

    @BeforeEach
    void cleanStore() {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE document_source");
    }

    @Test
    void syncIngestsPagesWithConfluenceMetadataAndCountsThem() {
        when(client.spaceId("DOCS")).thenReturn("111");
        when(client.pages("111")).thenReturn(List.of(
                page("1", "Kafka", "<p>Kafka is an event streaming platform.</p>"),
                page("2", "Redis", "<p>Redis is an in-memory data store.</p>")));

        ConfluenceSyncService.SyncResult result = syncService.sync("DOCS");

        assertThat(result.ingested()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(result.removed()).isZero();

        var results = searchService.search("event streaming", 3);
        assertThat(results).isNotEmpty();
        Integer type = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_source WHERE doc_type = 'CONFLUENCE'", Integer.class);
        assertThat(type).isEqualTo(2);
    }

    @Test
    void secondSyncSkipsUnchangedPages() {
        when(client.spaceId("DOCS")).thenReturn("111");
        when(client.pages("111")).thenReturn(List.of(page("1", "Kafka", "<p>same</p>")));

        syncService.sync("DOCS");
        ConfluenceSyncService.SyncResult second = syncService.sync("DOCS");

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.ingested()).isZero();
    }

    @Test
    void stalePagesAreRemovedWhenNoLongerEnumerated() {
        when(client.spaceId("DOCS")).thenReturn("111");
        when(client.pages("111")).thenReturn(List.of(
                page("1", "Keep", "<p>keep</p>"),
                page("2", "Drop", "<p>drop</p>")));
        syncService.sync("DOCS");

        when(client.pages("111")).thenReturn(List.of(page("1", "Keep", "<p>keep</p>")));
        ConfluenceSyncService.SyncResult second = syncService.sync("DOCS");

        assertThat(second.removed()).isEqualTo(1);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_source WHERE doc_type = 'CONFLUENCE'", Integer.class);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void perPageFailureIsCountedNotFatal() {
        when(client.spaceId("DOCS")).thenReturn("111");
        // An empty body yields a JsoupDocumentReader that produces zero documents but does not fail;
        // to force a failure we pass content Jsoup can read yet still land a valid page alongside it.
        when(client.pages("111")).thenReturn(List.of(
                page("1", "Good", "<p>good content</p>"),
                new ConfluencePage("2", "DOCS", "Bad", null,
                        "https://c.atlassian.net/wiki/spaces/DOCS/pages/2/Bad")));

        ConfluenceSyncService.SyncResult result = syncService.sync("DOCS");

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.ingested()).isEqualTo(1);
    }
}
