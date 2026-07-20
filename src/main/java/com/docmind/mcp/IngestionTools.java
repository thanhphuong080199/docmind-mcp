package com.docmind.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class IngestionTools {

    private final IngestionService ingestionService;

    public IngestionTools(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @McpTool(name = "ingest_document", description = """
            Ingest a Markdown (.md) or PDF (.pdf) file from a local filesystem path \
            into the documentation index so it becomes searchable. Re-ingesting an \
            unchanged file is a no-op; a changed file replaces its previous chunks.""")
    public String ingestDocument(
            @McpToolParam(description = "Absolute path to a .md or .pdf file on the server machine") String path) {
        try {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file)) {
                return "Error: no file at " + path;
            }
            DocumentSource doc = ingestionService.ingestFile(file);
            return "Ingested '%s' (id=%s, %d chunks, status=%s)"
                    .formatted(doc.title(), doc.id(), doc.chunkCount(), doc.status());
        }
        catch (Exception e) {
            return "Error: could not ingest " + path + " — " + e.getMessage();
        }
    }

    @McpTool(name = "ingest_url", description = """
            Fetch a web page and ingest its text content into the documentation index. \
            Re-ingesting an unchanged page is a no-op; a changed page replaces its chunks.""")
    public String ingestUrl(
            @McpToolParam(description = "Absolute http(s) URL of the page") String url,
            @McpToolParam(description = "Optional display title (defaults to the URL)", required = false) String title) {
        try {
            DocumentSource doc = ingestionService.ingestUrl(url, title);
            return "Ingested '%s' (id=%s, %d chunks, status=%s)"
                    .formatted(doc.title(), doc.id(), doc.chunkCount(), doc.status());
        }
        catch (Exception e) {
            return "Error: could not ingest " + url + " — " + e.getMessage();
        }
    }

    @McpTool(name = "remove_document", description = """
            Remove an ingested document and all its chunks from the index by document id \
            (ids appear in search_docs results and list_available_docs).""")
    public String removeDocument(
            @McpToolParam(description = "Document id (UUID)") String docId) {
        UUID id;
        try {
            id = UUID.fromString(docId);
        }
        catch (IllegalArgumentException e) {
            return "Error: '" + docId + "' is not a valid document id";
        }
        return ingestionService.removeDocument(id)
                ? "Removed document " + docId
                : "No document with id " + docId;
    }
}
