package com.docmind.mcp;

import java.util.List;

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
}
