package com.docmind;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionService;
import com.docmind.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
class DemoIngestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoIngestRunner.class);

    private final IngestionService ingestionService;
    private final SearchService searchService;

    DemoIngestRunner(IngestionService ingestionService, SearchService searchService) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
    }

    @Override
    public void run(String... args) {
        DocumentSource doc = ingestionService.ingestMarkdown(
                new ClassPathResource("samples/spring-boot-overview.md"),
                "Spring Boot Overview");
        log.info("Ingested '{}' as {} ({} chunks)", doc.title(), doc.id(), doc.chunkCount());

        String query = "How does auto-configuration work?";
        log.info("Query: {}", query);
        searchService.search(query, 3).forEach(result ->
                log.info("  [score={}] {}", result.score(),
                        result.content().substring(0, Math.min(120, result.content().length()))));
    }
}
