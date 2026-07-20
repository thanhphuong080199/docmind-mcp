package com.docmind.confluence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.docmind.config.DocmindProperties;
import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import com.docmind.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docmind.confluence.base-url")
public class ConfluenceSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceSyncService.class);

    private final ConfluenceClient client;
    private final IngestionService ingestionService;
    private final DocumentSourceRepository repository;
    private final String baseUrl;
    private final List<String> configuredSpaceKeys;

    public ConfluenceSyncService(ConfluenceClient client,
                                 IngestionService ingestionService,
                                 DocumentSourceRepository repository,
                                 DocmindProperties properties) {
        this.client = client;
        this.ingestionService = ingestionService;
        this.repository = repository;
        this.baseUrl = properties.confluence().baseUrl();
        this.configuredSpaceKeys = properties.confluence().spaceKeys();
    }

    public List<String> configuredSpaceKeys() {
        return configuredSpaceKeys;
    }

    public SyncResult sync(String spaceKey) {
        String spaceId = client.spaceId(spaceKey);
        List<ConfluencePage> pages = client.pages(spaceId);   // full enumeration; throws abort the sync

        int ingested = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> seen = new HashSet<>();
        for (ConfluencePage page : pages) {
            String sourceUri = sourceUri(page.spaceKey(), page.id());
            seen.add(sourceUri);
            try {
                IngestionService.IngestOutcome outcome =
                        ingestionService.ingestConfluencePage(sourceUri, page.title(), page.body());
                if (outcome.skipped()) {
                    skipped++;
                }
                else {
                    ingested++;
                }
            }
            catch (RuntimeException e) {
                log.warn("Failed to ingest Confluence page {}: {}", page.id(), e.getMessage());
                failed++;
            }
        }

        int removed = removeStale(spaceKey, seen);
        return new SyncResult(spaceKey, ingested, skipped, failed, removed);
    }

    public DocumentSource ingestPage(String pageId) {
        ConfluencePage page = client.page(pageId);
        String sourceUri = sourceUri(page.spaceKey(), page.id());
        return ingestionService.ingestConfluencePage(sourceUri, page.title(), page.body()).document();
    }

    private int removeStale(String spaceKey, Set<String> seen) {
        String prefix = baseUrl + "/wiki/spaces/" + spaceKey + "/pages/";
        int removed = 0;
        for (DocumentSource doc : repository.findByDocTypeAndSourceUriStartingWith("CONFLUENCE", prefix)) {
            if (!seen.contains(doc.sourceUri())) {
                ingestionService.removeDocument(doc.id());
                removed++;
            }
        }
        return removed;
    }

    private String sourceUri(String spaceKey, String pageId) {
        return baseUrl + "/wiki/spaces/" + spaceKey + "/pages/" + pageId;
    }

    public record SyncResult(String spaceKey, int ingested, int skipped, int failed, int removed) {
    }
}
