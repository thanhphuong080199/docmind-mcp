package com.docmind.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.Resource;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Service;

@Service
public class IngestionService {

    private final VectorStore vectorStore;
    private final DocumentSourceRepository repository;
    private final JdbcAggregateTemplate aggregateTemplate;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder().build();

    public IngestionService(VectorStore vectorStore,
                            DocumentSourceRepository repository,
                            JdbcAggregateTemplate aggregateTemplate) {
        this.vectorStore = vectorStore;
        this.repository = repository;
        this.aggregateTemplate = aggregateTemplate;
    }

    public DocumentSource ingestMarkdown(Resource resource, String title) {
        String sourceUri = sourceUriOf(resource);
        String checksum = sha256(resource);

        repository.findBySourceUri(sourceUri).ifPresent(this::removeExisting);

        UUID docId = UUID.randomUUID();
        MarkdownDocumentReader reader = new MarkdownDocumentReader(
                resource,
                MarkdownDocumentReaderConfig.builder()
                        .withIncludeCodeBlock(true)
                        .build());

        List<Document> chunks = splitter.apply(reader.get());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("doc_id", docId.toString());
            chunk.getMetadata().put("source_uri", sourceUri);
            chunk.getMetadata().put("chunk_index", i);
        }
        vectorStore.add(chunks);

        return aggregateTemplate.insert(new DocumentSource(
                docId, title, sourceUri, "MARKDOWN", checksum,
                chunks.size(), null, "INGESTED", Instant.now()));
    }

    private void removeExisting(DocumentSource existing) {
        vectorStore.delete(new FilterExpressionBuilder()
                .eq("doc_id", existing.id().toString())
                .build());
        repository.delete(existing);
    }

    private static String sourceUriOf(Resource resource) {
        try {
            return resource.getURI().toString();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve URI for " + resource, e);
        }
    }

    private static String sha256(Resource resource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(resource.getContentAsByteArray()));
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
