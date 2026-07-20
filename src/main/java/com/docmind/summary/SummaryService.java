package com.docmind.summary;

import java.util.List;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SummaryService {

    private final DocumentSourceRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final LlmSummarizer summarizer;

    public SummaryService(DocumentSourceRepository repository,
                          JdbcTemplate jdbcTemplate,
                          LlmSummarizer summarizer) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.summarizer = summarizer;
    }

    public String getSummary(UUID docId) {
        DocumentSource doc = repository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("No document with id " + docId));
        if (doc.summary() != null && !doc.summary().isBlank()) {
            return doc.summary();
        }
        List<String> chunks = jdbcTemplate.queryForList("""
                SELECT content FROM vector_store
                WHERE metadata->>'doc_id' = ?
                ORDER BY (metadata->>'chunk_index')::int
                LIMIT 20
                """, String.class, docId.toString());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Document " + docId + " has no chunks to summarize");
        }
        String summary = summarizer.summarize(String.join("\n\n", chunks));
        repository.save(doc.withSummary(summary));
        return summary;
    }
}
