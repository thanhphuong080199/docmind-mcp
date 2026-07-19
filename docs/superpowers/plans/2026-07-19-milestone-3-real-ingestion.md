# Milestone 3: Real Ingestion (checksum dedup, PDF, folder scan, ingest/remove tools) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest arbitrary Markdown and PDF files from the filesystem with checksum-based skip/re-ingest, a startup folder scan, and MCP tools `ingest_document` / `remove_document`.

**Architecture:** `IngestionService` is generalized: a `doIngest` core takes a source URI, doc type, checksum and a lazy reader; `ingestFile(Path)` dispatches Markdown vs PDF by extension. Unchanged files (same SHA-256) are skipped; changed files replace their chunks; reader/embedding failures record a `FAILED` `document_source` row without breaking other documents. A `DocsFolderScanner` walks a configured folder (`docmind.docs-dir`) and is optionally triggered at startup. Thin `IngestionTools` exposes the two MCP tools with structured error strings.

**Tech Stack:** Spring AI 2.0.0 `spring-ai-pdf-document-reader` (`PagePdfDocumentReader`, Apache PDFBox 3.x transitively), `@ConfigurationProperties`, existing pgvector/Ollama stack.

## Global Constraints

- Java 25; prefix every Maven call: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd ...` (PowerShell). Wrapper only.
- Tests need `docker compose up -d` with `nomic-embed-text` pulled (mirror.gcr.io workaround in README if pulls fail).
- Spring AI BOM 2.0.0 manages `org.springframework.ai` versions — no explicit versions.
- Chunk metadata keys exactly `doc_id`, `source_uri`, `chunk_index`.
- `document_source.status` values: `INGESTED | FAILED | STALE` (spec); `doc_type` values: `MARKDOWN | PDF | WEB`.
- Tool calls return structured messages, never stack traces.
- MCP layer stays thin — logic in `com.docmind.ingestion`, delegation in `com.docmind.mcp`.
- Work on branch `milestone-3-real-ingestion` cut from `main` after the Milestone 2 PR merges.

---

### Task 1: Filesystem ingestion with checksum skip (Markdown)

**Files:**
- Modify: `src/main/java/com/docmind/ingestion/IngestionService.java` (full rewrite below)
- Test: `src/test/java/com/docmind/ingestion/IngestionServiceFileTest.java`

**Interfaces:**
- Consumes: existing `DocumentSource` record, `DocumentSourceRepository.findBySourceUri`, `VectorStore`, `JdbcAggregateTemplate`.
- Produces: `DocumentSource ingestFile(Path path)` (title = file name without extension) and `DocumentSource ingestFile(Path path, String title)`; unchanged file (same SHA-256, status `INGESTED`) returns the existing row without touching the vector store. `ingestMarkdown(Resource, String)` keeps its exact Milestone 1 signature (demo runner + old tests). Tasks 2–5 build on `ingestFile`.

- [ ] **Step 1: Write the failing test**

```java
package com.docmind.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;

import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class IngestionServiceFileTest {

    @Autowired
    IngestionService ingestionService;

    @Autowired
    DocumentSourceRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    @BeforeEach
    void cleanStore() {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE document_source");
    }

    @Test
    void ingestsMarkdownFileFromFilesystem() throws Exception {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");

        DocumentSource doc = ingestionService.ingestFile(file);

        assertThat(doc.title()).isEqualTo("notes");
        assertThat(doc.docType()).isEqualTo("MARKDOWN");
        assertThat(doc.status()).isEqualTo("INGESTED");
        assertThat(doc.chunkCount()).isGreaterThan(0);
        assertThat(doc.sourceUri()).startsWith("file:");
    }

    @Test
    void unchangedFileIsSkippedOnReingest() throws Exception {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");

        DocumentSource first = ingestionService.ingestFile(file);
        DocumentSource second = ingestionService.ingestFile(file);

        assertThat(second.id()).isEqualTo(first.id()); // re-ingest would mint a new id
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void changedFileIsReingestedWithNewChunks() throws Exception {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        DocumentSource first = ingestionService.ingestFile(file);

        Files.writeString(file, "# Kafka\n\nKafka now also does queues (KIP-932).\n");
        DocumentSource second = ingestionService.ingestFile(file);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(repository.count()).isEqualTo(1);
        Integer chunkRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM vector_store WHERE metadata->>'doc_id' = ?",
                Integer.class, second.id().toString());
        assertThat(chunkRows).isEqualTo(second.chunkCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestionServiceFileTest`
Expected: COMPILE FAIL — no `ingestFile` method.

- [ ] **Step 3: Rewrite IngestionService**

Replace `src/main/java/com/docmind/ingestion/IngestionService.java` entirely:

```java
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
```

- [ ] **Step 4: Run tests (new + regression)**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green, including Milestone 1's `IngestAndSearchIntegrationTest` (behavioral note: re-running the demo with an unchanged sample doc now skips instead of replacing — that is the desired behavior).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/ingestion/IngestionService.java src/test/java/com/docmind/ingestion/IngestionServiceFileTest.java
git commit -m "feat: filesystem ingestion with SHA-256 checksum skip"
```

---

### Task 2: PDF support and FAILED status on ingestion errors

**Files:**
- Modify: `pom.xml` (dependencies block)
- Modify: `src/main/java/com/docmind/ingestion/IngestionService.java` (three methods shown below)
- Create: `src/main/java/com/docmind/ingestion/IngestionException.java`
- Test: `src/test/java/com/docmind/ingestion/IngestionServiceFileTest.java` (add two tests)

**Interfaces:**
- Consumes: Task 1's `ingestFile(Path)` and `doIngest`.
- Produces: `.pdf` files ingest with `docType = "PDF"`; any read/split/embed failure records a `FAILED` `document_source` row (chunkCount 0) and throws `IngestionException` (a `RuntimeException` carrying a client-safe message). Task 4's scanner and Task 5's tool catch it per file.

- [ ] **Step 1: Add the PDF reader dependency**

In `pom.xml` `<dependencies>`:

```xml
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-pdf-document-reader</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing tests**

Add to `IngestionServiceFileTest` (PDFBox comes in transitively with the reader):

```java
    @Test
    void ingestsPdfFile() throws Exception {
        Path file = tempDir.resolve("vectors.pdf");
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText("Vector databases store embeddings for semantic similarity search.");
                cs.endText();
            }
            pdf.save(file.toFile());
        }

        DocumentSource doc = ingestionService.ingestFile(file);

        assertThat(doc.docType()).isEqualTo("PDF");
        assertThat(doc.status()).isEqualTo("INGESTED");
        assertThat(doc.chunkCount()).isGreaterThan(0);
    }

    @Test
    void unreadablePdfIsMarkedFailed() throws Exception {
        Path file = tempDir.resolve("broken.pdf");
        Files.writeString(file, "this is not a pdf");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ingestionService.ingestFile(file))
                .isInstanceOf(IngestionException.class);

        DocumentSource failed = repository.findBySourceUri(
                file.toAbsolutePath().normalize().toUri().toString()).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.chunkCount()).isZero();
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestionServiceFileTest`
Expected: FAIL — `ingestsPdfFile` with `Unsupported file type`, `unreadablePdfIsMarkedFailed` compile fail (no `IngestionException`).

- [ ] **Step 4: Create IngestionException**

```java
package com.docmind.ingestion;

public class IngestionException extends RuntimeException {

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Implement PDF dispatch and failure recording**

In `IngestionService`, add the import `org.springframework.ai.reader.pdf.PagePdfDocumentReader` and replace these three methods:

```java
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
        try {
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
        catch (RuntimeException e) {
            aggregateTemplate.insert(new DocumentSource(
                    docId, title, sourceUri, docType, checksum,
                    0, null, "FAILED", Instant.now()));
            throw new IngestionException("Failed to ingest " + sourceUri + ": " + e.getMessage(), e);
        }
    }

    private List<Document> readDocuments(String docType, Resource resource) {
        return switch (docType) {
            case "MARKDOWN" -> new MarkdownDocumentReader(resource,
                    MarkdownDocumentReaderConfig.builder().withIncludeCodeBlock(true).build()).get();
            case "PDF" -> new PagePdfDocumentReader(resource).get();
            default -> throw new IllegalArgumentException("Unsupported doc type: " + docType);
        };
    }

    private static String docTypeOf(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            return "MARKDOWN";
        }
        if (name.endsWith(".pdf")) {
            return "PDF";
        }
        throw new IllegalArgumentException("Unsupported file type: " + path.getFileName());
    }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestionServiceFileTest`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/docmind/ingestion/ src/test/java/com/docmind/ingestion/
git commit -m "feat: PDF ingestion and FAILED status on ingest errors"
```

---

### Task 3: removeDocument in IngestionService

**Files:**
- Modify: `src/main/java/com/docmind/ingestion/IngestionService.java` (one new public method)
- Test: `src/test/java/com/docmind/ingestion/IngestionServiceFileTest.java` (add one test)

**Interfaces:**
- Consumes: `removeExisting` from Task 1, `DocumentSourceRepository.findById`.
- Produces: `boolean removeDocument(UUID docId)` — true if a document was deleted (chunks + metadata row), false if the id is unknown. Task 5's `remove_document` tool and Milestone 4's catalog rely on it.

- [ ] **Step 1: Write the failing test**

Add to `IngestionServiceFileTest` (also autowire `SearchService`: add field `@Autowired com.docmind.search.SearchService searchService;`):

```java
    @Test
    void removeDocumentDeletesChunksAndMetadata() throws Exception {
        Path file = tempDir.resolve("notes.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        DocumentSource doc = ingestionService.ingestFile(file);

        boolean removed = ingestionService.removeDocument(doc.id());

        assertThat(removed).isTrue();
        assertThat(repository.count()).isZero();
        assertThat(searchService.search("Kafka", 5, doc.id().toString())).isEmpty();
        assertThat(ingestionService.removeDocument(doc.id())).isFalse();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestionServiceFileTest`
Expected: COMPILE FAIL — no `removeDocument` method.

- [ ] **Step 3: Implement**

Add to `IngestionService` after `ingestMarkdown`:

```java
    public boolean removeDocument(UUID docId) {
        return repository.findById(docId)
                .map(doc -> {
                    removeExisting(doc);
                    return true;
                })
                .orElse(false);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestionServiceFileTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/ingestion/IngestionService.java src/test/java/com/docmind/ingestion/IngestionServiceFileTest.java
git commit -m "feat: removeDocument deletes chunks and metadata"
```

---

### Task 4: Configured docs folder scan (optional at startup)

**Files:**
- Create: `src/main/java/com/docmind/config/DocmindProperties.java`
- Create: `src/main/java/com/docmind/ingestion/DocsFolderScanner.java`
- Create: `src/main/java/com/docmind/ingestion/StartupScanRunner.java`
- Modify: `src/main/java/com/docmind/DocmindApplication.java` (add `@ConfigurationPropertiesScan`)
- Modify: `src/main/resources/application.yml` (add `docmind:` block)
- Modify: `.gitignore` (ignore the default inbox folder)
- Test: `src/test/java/com/docmind/ingestion/DocsFolderScannerTest.java`

**Interfaces:**
- Consumes: `ingestFile(Path)` from Task 1, `IngestionException` from Task 2.
- Produces: `DocsFolderScanner.scan(Path dir)` returning `ScanReport(int succeeded, int failed)`, skipping unsupported files, never aborting on a single failure; `DocmindProperties(Path docsDir, boolean scanOnStartup)` bound to prefix `docmind`; startup scan only when `docmind.scan-on-startup=true`. Milestone 6's re-scan scheduler reuses `scan`.

- [ ] **Step 1: Write the failing test**

```java
package com.docmind.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;

import com.docmind.domain.DocumentSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class DocsFolderScannerTest {

    @Autowired
    DocsFolderScanner scanner;

    @Autowired
    DocumentSourceRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDir;

    @BeforeEach
    void cleanStore() {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE document_source");
    }

    @Test
    void scansFolderIngestingSupportedFilesAndSurvivingFailures() throws Exception {
        Files.writeString(tempDir.resolve("one.md"), "# One\n\nContent about topic one.\n");
        Files.writeString(tempDir.resolve("two.md"), "# Two\n\nContent about topic two.\n");
        Files.writeString(tempDir.resolve("broken.pdf"), "not a pdf");   // must fail, not abort
        Files.writeString(tempDir.resolve("ignored.txt"), "unsupported"); // must be skipped

        DocsFolderScanner.ScanReport report = scanner.scan(tempDir);

        assertThat(report.succeeded()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(3); // 2 INGESTED + 1 FAILED row
    }

    @Test
    void missingFolderYieldsEmptyReport() {
        DocsFolderScanner.ScanReport report = scanner.scan(tempDir.resolve("does-not-exist"));
        assertThat(report.succeeded()).isZero();
        assertThat(report.failed()).isZero();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=DocsFolderScannerTest`
Expected: COMPILE FAIL — no `DocsFolderScanner`.

- [ ] **Step 3: Implement scanner, properties, startup runner**

`src/main/java/com/docmind/ingestion/DocsFolderScanner.java`:

```java
package com.docmind.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DocsFolderScanner {

    private static final Logger log = LoggerFactory.getLogger(DocsFolderScanner.class);

    private final IngestionService ingestionService;

    public DocsFolderScanner(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    public ScanReport scan(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.info("Docs folder {} does not exist, nothing to scan", dir);
            return new ScanReport(0, 0);
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(DocsFolderScanner::isSupported).sorted().toList();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot scan " + dir, e);
        }

        int succeeded = 0;
        int failed = 0;
        for (Path file : files) {
            try {
                ingestionService.ingestFile(file);
                succeeded++;
            }
            catch (RuntimeException e) {
                log.warn("Skipping {}: {}", file, e.getMessage());
                failed++;
            }
        }
        log.info("Scanned {}: {} ingested/unchanged, {} failed", dir, succeeded, failed);
        return new ScanReport(succeeded, failed);
    }

    private static boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return Files.isRegularFile(path)
                && (name.endsWith(".md") || name.endsWith(".markdown") || name.endsWith(".pdf"));
    }

    public record ScanReport(int succeeded, int failed) {
    }
}
```

`src/main/java/com/docmind/config/DocmindProperties.java`:

```java
package com.docmind.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docmind")
public record DocmindProperties(Path docsDir, boolean scanOnStartup) {

    public DocmindProperties {
        if (docsDir == null) {
            docsDir = Path.of("docs-inbox");
        }
    }
}
```

`src/main/java/com/docmind/ingestion/StartupScanRunner.java`:

```java
package com.docmind.ingestion;

import com.docmind.config.DocmindProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.scan-on-startup", havingValue = "true")
public class StartupScanRunner implements ApplicationRunner {

    private final DocsFolderScanner scanner;
    private final DocmindProperties properties;

    public StartupScanRunner(DocsFolderScanner scanner, DocmindProperties properties) {
        this.scanner = scanner;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        scanner.scan(properties.docsDir());
    }
}
```

In `DocmindApplication.java` add `@ConfigurationPropertiesScan` (import `org.springframework.boot.context.properties.ConfigurationPropertiesScan`) next to `@SpringBootApplication`.

In `application.yml` add at top level (not under `spring:`):

```yaml
docmind:
  docs-dir: ./docs-inbox
  scan-on-startup: false
```

In `.gitignore` add a line: `docs-inbox/`

- [ ] **Step 4: Run tests (new + full regression)**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green (`scan-on-startup` is false, so no runner interferes with tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/ src/main/resources/application.yml .gitignore src/test/java/com/docmind/ingestion/DocsFolderScannerTest.java
git commit -m "feat: configurable docs folder scan with optional startup run"
```

---

### Task 5: ingest_document and remove_document MCP tools

**Files:**
- Create: `src/main/java/com/docmind/mcp/IngestionTools.java`
- Test: `src/test/java/com/docmind/mcp/IngestionToolsTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `ingestFile(Path)` (Task 1), `removeDocument(UUID)` (Task 3), `IngestionException` (Task 2), `DocumentSource` accessors `id() title() chunkCount() status()`.
- Produces: MCP tools `ingest_document(path)` and `remove_document(docId)`, both returning human-readable `String` results (`Error: ...` on failure, never a stack trace).

- [ ] **Step 1: Write the failing unit test**

```java
package com.docmind.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionException;
import com.docmind.ingestion.IngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionToolsTest {

    @Mock
    IngestionService ingestionService;

    @InjectMocks
    IngestionTools tools;

    @TempDir
    Path tempDir;

    @Test
    void ingestReportsSuccess() throws Exception {
        Path file = tempDir.resolve("doc.md");
        Files.writeString(file, "# Doc");
        UUID id = UUID.randomUUID();
        when(ingestionService.ingestFile(any(Path.class))).thenReturn(new DocumentSource(
                id, "doc", file.toUri().toString(), "MARKDOWN", "abc", 3, null, "INGESTED", Instant.now()));

        assertThat(tools.ingestDocument(file.toString()))
                .contains(id.toString()).contains("3 chunks").contains("INGESTED");
    }

    @Test
    void ingestMissingFileReturnsErrorMessage() {
        assertThat(tools.ingestDocument(tempDir.resolve("nope.md").toString()))
                .startsWith("Error:");
    }

    @Test
    void ingestFailureReturnsErrorMessageNotStackTrace() throws Exception {
        Path file = tempDir.resolve("bad.pdf");
        Files.writeString(file, "x");
        when(ingestionService.ingestFile(any(Path.class)))
                .thenThrow(new IngestionException("Failed to ingest: broken", new RuntimeException()));

        assertThat(tools.ingestDocument(file.toString())).startsWith("Error:").contains("broken");
    }

    @Test
    void removeReportsFoundAndNotFound() {
        UUID id = UUID.randomUUID();
        when(ingestionService.removeDocument(id)).thenReturn(true);
        assertThat(tools.removeDocument(id.toString())).contains("Removed");

        UUID unknown = UUID.randomUUID();
        when(ingestionService.removeDocument(unknown)).thenReturn(false);
        assertThat(tools.removeDocument(unknown.toString())).contains("No document");
    }

    @Test
    void removeRejectsInvalidUuid() {
        assertThat(tools.removeDocument("not-a-uuid")).startsWith("Error:");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestionToolsTest`
Expected: COMPILE FAIL — no `IngestionTools`.

- [ ] **Step 3: Implement IngestionTools**

```java
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
```

- [ ] **Step 4: Run all tests**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green.

- [ ] **Step 5: Manual check with MCP Inspector**

Start the app (`docker compose up -d`, then `mvnw.cmd spring-boot:run` with JAVA_HOME prefix), connect Inspector to `http://localhost:8080/mcp`, call `ingest_document` with an absolute path to any local `.md` file, then `search_docs` for its content, then `remove_document` with the returned id.
Expected: ingest reports chunks; search finds content; remove confirms; repeat search returns nothing.

- [ ] **Step 6: Update README and commit**

Update the README status line to `Work in progress — currently at Milestone 3 (real ingestion: PDF, dedup, folder scan).`, extend the tools list to `search_docs`, `ingest_document`, `remove_document`, and add under "Using the MCP server":

```markdown
Drop `.md`/`.pdf` files into `./docs-inbox` and set `docmind.scan-on-startup: true`
(or call the `ingest_document` tool) to index them. Unchanged files are skipped by
SHA-256 checksum; changed files are re-ingested.
```

```bash
git add src/main/java/com/docmind/mcp/IngestionTools.java src/test/java/com/docmind/mcp/IngestionToolsTest.java README.md
git commit -m "feat: ingest_document and remove_document MCP tools"
```

---

## Final verification

- [ ] `.\mvnw.cmd test` all green.
- [ ] Inspector round-trip: ingest → search → remove.
- [ ] Finish with superpowers:finishing-a-development-branch.
