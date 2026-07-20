package com.docmind.mcp;

import java.util.List;
import java.util.UUID;

import com.docmind.search.SearchService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class SearchTools {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final SearchService searchService;

    public SearchTools(SearchService searchService) {
        this.searchService = searchService;
    }

    @McpTool(name = "search_docs", description = """
            Semantic search over the user's ingested technical documentation. \
            Returns the most relevant chunks with source document id, source URI \
            and a similarity score between 0 and 1 (higher is more relevant). \
            Use this to ground answers in the user's own docs.""")
    public List<SearchService.SearchResult> searchDocs(
            @McpToolParam(description = "Natural-language search query") String query,
            @McpToolParam(description = "Max chunks to return, 1-20 (default 5)", required = false) Integer topK,
            @McpToolParam(description = "Restrict search to one document id", required = false) String docId) {
        int k = (topK == null || topK < 1) ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        return searchService.search(query, k, docId);
    }

    @McpTool(name = "get_chunk_context", description = """
            Fetch the chunks surrounding a search hit (same document, neighboring \
            chunk_index values) to get more context than a single chunk provides. \
            Use the docId and chunkIndex from a search_docs result.""")
    public List<SearchService.ChunkContext> getChunkContext(
            @McpToolParam(description = "Document id (UUID) from a search result") String docId,
            @McpToolParam(description = "chunk_index of the hit") int chunkIndex,
            @McpToolParam(description = "Neighbors on each side, 1-5 (default 1)", required = false) Integer window) {
        return searchService.chunkContext(UUID.fromString(docId), chunkIndex,
                window == null ? 1 : window);
    }
}
