package com.docmind.search;

import java.util.List;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private final VectorStore vectorStore;

    public SearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<SearchResult> search(String query, int topK) {
        return search(query, topK, null);
    }

    public List<SearchResult> search(String query, int topK, String docId) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(query)
                .topK(topK);
        if (docId != null && !docId.isBlank()) {
            request.filterExpression(new FilterExpressionBuilder()
                    .eq("doc_id", docId)
                    .build());
        }
        return vectorStore.similaritySearch(request.build())
                .stream()
                .map(doc -> new SearchResult(
                        (String) doc.getMetadata().get("doc_id"),
                        (String) doc.getMetadata().get("source_uri"),
                        doc.getText(),
                        doc.getScore()))
                .toList();
    }

    public record SearchResult(String docId, String sourceUri, String content, Double score) {
    }
}
