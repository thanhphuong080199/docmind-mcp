package com.docmind.mcp;

import com.docmind.confluence.ConfluenceSyncService;
import com.docmind.confluence.ConfluenceSyncService.SyncResult;
import com.docmind.domain.DocumentSource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.confluence.base-url")
public class ConfluenceTools {

    private final ConfluenceSyncService syncService;

    public ConfluenceTools(ConfluenceSyncService syncService) {
        this.syncService = syncService;
    }

    @McpTool(name = "sync_confluence", description = """
            Sync Confluence Cloud pages into the documentation index. With no space key, \
            syncs every configured space; with a space key, syncs just that space. \
            Unchanged pages are skipped; pages removed from Confluence are removed from the index.""")
    public String syncConfluence(
            @McpToolParam(description = "Optional Confluence space key (e.g. DOCS); omit to sync all configured spaces",
                    required = false) String spaceKey) {
        if (spaceKey != null && !spaceKey.isBlank()) {
            return syncOne(spaceKey.trim());
        }
        StringBuilder out = new StringBuilder();
        for (String key : syncService.configuredSpaceKeys()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(syncOne(key));
        }
        return out.length() == 0 ? "No Confluence spaces configured" : out.toString();
    }

    @McpTool(name = "ingest_confluence_page", description = """
            Fetch a single Confluence Cloud page by its page id and ingest it into the \
            documentation index. Re-ingesting an unchanged page is a no-op.""")
    public String ingestConfluencePage(
            @McpToolParam(description = "Confluence page id (numeric string)") String pageId) {
        try {
            DocumentSource doc = syncService.ingestPage(pageId);
            return "Ingested '%s' (id=%s, %d chunks, status=%s)"
                    .formatted(doc.title(), doc.id(), doc.chunkCount(), doc.status());
        }
        catch (Exception e) {
            return "Error: could not ingest Confluence page " + pageId + " — " + e.getMessage();
        }
    }

    private String syncOne(String spaceKey) {
        try {
            SyncResult r = syncService.sync(spaceKey);
            return "Synced space %s: %d ingested, %d skipped, %d failed, %d removed"
                    .formatted(r.spaceKey(), r.ingested(), r.skipped(), r.failed(), r.removed());
        }
        catch (Exception e) {
            return "Error: could not sync Confluence space " + spaceKey + " — " + e.getMessage();
        }
    }
}
