package com.docmind.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogToolsTest {

    @Mock
    DocumentSourceRepository repository;

    CatalogTools tools;

    UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tools = new CatalogTools(repository, new JsonMapper());
        when(repository.findAll()).thenReturn(List.of(new DocumentSource(
                id, "Kafka Guide", "file:///docs/kafka.md", "MARKDOWN", "abc",
                7, "A summary", "INGESTED", Instant.parse("2026-07-19T10:00:00Z"))));
    }

    @Test
    void listsDocsWithSummaryFlag() {
        List<CatalogTools.DocInfo> docs = tools.listAvailableDocs();

        assertThat(docs).hasSize(1);
        CatalogTools.DocInfo info = docs.getFirst();
        assertThat(info.id()).isEqualTo(id.toString());
        assertThat(info.title()).isEqualTo("Kafka Guide");
        assertThat(info.chunkCount()).isEqualTo(7);
        assertThat(info.hasSummary()).isTrue();
    }

    @Test
    void resourceServesJson() {
        String json = tools.docsResource();

        assertThat(json).contains("\"title\":\"Kafka Guide\"").contains(id.toString());
    }
}
