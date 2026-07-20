package com.docmind.search;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private final VectorStore vectorStore;
    private final double similarityThreshold;
    private final JdbcTemplate jdbcTemplate;

    public SearchService(VectorStore vectorStore, com.docmind.config.DocmindProperties properties,
                         JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = properties.similarityThreshold();
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SearchResult> search(String query, int topK) {
        return search(query, topK, null);
    }

    public List<SearchResult> search(String query, int topK, String docId) {
        SearchRequest.Builder request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold);
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
                        ((Number) doc.getMetadata().get("chunk_index")).intValue(),
                        doc.getText(),
                        doc.getScore()))
                .toList();
    }

    public List<ChunkContext> chunkContext(UUID docId, int chunkIndex, int window) {
        int w = Math.clamp(window, 1, 5);
        return jdbcTemplate.query("""
                        SELECT (metadata->>'chunk_index')::int AS idx, content
                        FROM vector_store
                        WHERE metadata->>'doc_id' = ?
                          AND (metadata->>'chunk_index')::int BETWEEN ? AND ?
                        ORDER BY idx
                        """,
                (rs, rowNum) -> new ChunkContext(rs.getInt("idx"), rs.getString("content")),
                docId.toString(), chunkIndex - w, chunkIndex + w);
    }

    public record SearchResult(String docId, String sourceUri, int chunkIndex,
                               String content, Double score) {
    }

    public record ChunkContext(int chunkIndex, String content) {
    }
}
