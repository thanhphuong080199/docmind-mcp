package com.docmind.mcp;

import java.util.List;

import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class CatalogTools {

    private final DocumentSourceRepository repository;
    private final ObjectMapper objectMapper;

    public CatalogTools(DocumentSourceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @McpTool(name = "list_available_docs", description = """
            List all ingested documents with their id, title, type, chunk count and status. \
            Use the id with search_docs (docId filter), get_doc_summary or remove_document.""")
    public List<DocInfo> listAvailableDocs() {
        return repository.findAll().stream().map(DocInfo::from).toList();
    }

    @McpResource(uri = "docmind://docs", name = "available-docs",
            description = "JSON list of all documents ingested into the docmind index",
            mimeType = "application/json")
    public String docsResource() {
        return objectMapper.writeValueAsString(listAvailableDocs());
    }

    public record DocInfo(String id, String title, String docType, int chunkCount,
                          String status, boolean hasSummary, String ingestedAt) {

        static DocInfo from(DocumentSource doc) {
            return new DocInfo(doc.id().toString(), doc.title(), doc.docType(),
                    doc.chunkCount() == null ? 0 : doc.chunkCount(), doc.status(),
                    doc.summary() != null && !doc.summary().isBlank(),
                    doc.ingestedAt().toString());
        }
    }
}
