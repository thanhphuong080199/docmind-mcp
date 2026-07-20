package com.docmind.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
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

    public DocumentSource ingestFile(Path path) {
        return ingestFile(path, titleOf(path));
    }

    public DocumentSource ingestFile(Path path, String title) {
        Path normalized = path.toAbsolutePath().normalize();
        String sourceUri = normalized.toUri().toString();
        String docType = docTypeOf(normalized);
        String checksum = sha256(readBytes(normalized));
        Resource resource = new FileSystemResource(normalized);
        return doIngest(sourceUri, docType, title, checksum, () -> readDocuments(docType, resource));
    }

    public DocumentSource ingestMarkdown(Resource resource, String title) {
        String sourceUri = sourceUriOf(resource);
        String checksum = sha256(contentOf(resource));
        return doIngest(sourceUri, "MARKDOWN", title, checksum, () -> readDocuments("MARKDOWN", resource));
    }

    private DocumentSource doIngest(String sourceUri, String docType, String title,
                                    String checksum, Supplier<List<Document>> readDocs) {
        var existing = repository.findBySourceUri(sourceUri);
        if (existing.isPresent()
                && checksum.equals(existing.get().checksum())
                && "INGESTED".equals(existing.get().status())) {
            return existing.get();
        }
        existing.ifPresent(this::removeExisting);

        UUID docId = UUID.randomUUID();
        List<Document> chunks = splitter.apply(readDocs.get());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("doc_id", docId.toString());
            chunk.getMetadata().put("source_uri", sourceUri);
            chunk.getMetadata().put("chunk_index", i);
        }
        vectorStore.add(chunks);

        return aggregateTemplate.insert(new DocumentSource(
                docId, title, sourceUri, docType, checksum,
                chunks.size(), null, "INGESTED", Instant.now()));
    }

    private List<Document> readDocuments(String docType, Resource resource) {
        return switch (docType) {
            case "MARKDOWN" -> new MarkdownDocumentReader(resource,
                    MarkdownDocumentReaderConfig.builder().withIncludeCodeBlock(true).build()).get();
            default -> throw new IllegalArgumentException("Unsupported doc type: " + docType);
        };
    }

    private void removeExisting(DocumentSource existing) {
        vectorStore.delete(new FilterExpressionBuilder()
                .eq("doc_id", existing.id().toString())
                .build());
        repository.delete(existing);
    }

    private static String docTypeOf(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        throw new IllegalArgumentException("Unsupported file type: " + path.getFileName());
    }

    private static String titleOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    private static byte[] contentOf(Resource resource) {
        try {
            return resource.getContentAsByteArray();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }

    private static String sourceUriOf(Resource resource) {
        try {
            return resource.getURI().toString();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot resolve URI for " + resource, e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
