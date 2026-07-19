# Milestone 6: Stretch (web ingestion, chunk context, periodic re-scan) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ingest web pages by URL, retrieve neighboring chunks around a search hit (small-to-big retrieval), and periodically re-scan the docs folder so changed files re-index automatically.

**Architecture:** Web ingestion reuses the Milestone 3 `doIngest` core: fetch URL bytes once, checksum them, parse with Spring AI's `JsoupDocumentReader`, store as `doc_type = "WEB"` with the URL as `source_uri`. Chunk context is a metadata-window SQL query over `vector_store` (`doc_id` + `chunk_index BETWEEN hit±window`), exposed as `get_chunk_context`; `SearchResult` gains a `chunkIndex` field so clients know where a hit sits. Re-scan is a `@Scheduled` wrapper around the existing `DocsFolderScanner` — checksum skip makes repeated scans cheap.

**Tech Stack:** `spring-ai-jsoup-document-reader`, Spring scheduling (`@EnableScheduling`), existing pgvector/Ollama stack.

## Global Constraints

- Java 25; prefix every Maven call: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd ...` (PowerShell). Wrapper only.
- Tests need `docker compose up -d` with `nomic-embed-text` (and `qwen3:4b`) pulled. Web-ingestion tests use `file://` URLs — no network dependency.
- Spring AI BOM 2.0.0; no explicit `org.springframework.ai` versions.
- Chunk metadata keys exactly `doc_id`, `source_uri`, `chunk_index`.
- `doc_type` values: `MARKDOWN | PDF | WEB`; tool errors are structured strings, never stack traces.
- These are stretch features — each task is independently shippable; stop after any task if priorities change.
- Work on branch `milestone-6-stretch` cut from `main` after the Milestone 5 PR merges.

---

### Task 1: Web page ingestion (ingest_url)

**Files:**
- Modify: `pom.xml` (dependencies block)
- Modify: `src/main/java/com/docmind/ingestion/IngestionService.java` (one new public method + one case in `readDocuments`)
- Modify: `src/main/java/com/docmind/mcp/IngestionTools.java` (new tool method)
- Test: `src/test/java/com/docmind/ingestion/IngestionServiceFileTest.java` (add one test)
- Test: `src/test/java/com/docmind/mcp/IngestionToolsTest.java` (add one test)

**Interfaces:**
- Consumes: Milestone 3's `doIngest(String sourceUri, String docType, String title, String checksum, Supplier<List<Document>> readDocs)` and `sha256(byte[])`.
- Produces: `DocumentSource ingestUrl(String url, String title)` (`title` null → the URL is used); MCP tool `ingest_url(url, title?)`. Re-ingesting an unchanged page is a no-op (checksum of fetched HTML).

- [ ] **Step 1: Add the Jsoup reader dependency**

In `pom.xml` `<dependencies>`:

```xml
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-jsoup-document-reader</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing tests**

Add to `IngestionServiceFileTest` (a `file://` URL keeps the test offline):

```java
    @Test
    void ingestsWebPageByUrl() throws Exception {
        Path html = tempDir.resolve("page.html");
        Files.writeString(html, """
                <html><head><title>Gradle Basics</title></head>
                <body><h1>Gradle</h1><p>Gradle builds projects using a task graph.</p></body></html>
                """);
        String url = html.toUri().toString();

        DocumentSource doc = ingestionService.ingestUrl(url, "Gradle Basics");

        assertThat(doc.docType()).isEqualTo("WEB");
        assertThat(doc.sourceUri()).isEqualTo(url);
        assertThat(doc.status()).isEqualTo("INGESTED");
        assertThat(doc.chunkCount()).isGreaterThan(0);
        assertThat(ingestionService.ingestUrl(url, "Gradle Basics").id()).isEqualTo(doc.id()); // checksum skip
    }
```

Add to `IngestionToolsTest`:

```java
    @Test
    void ingestUrlReportsSuccessAndErrors() {
        UUID id = UUID.randomUUID();
        when(ingestionService.ingestUrl("https://example.com/doc", null)).thenReturn(new DocumentSource(
                id, "https://example.com/doc", "https://example.com/doc", "WEB", "abc",
                2, null, "INGESTED", Instant.now()));

        assertThat(tools.ingestUrl("https://example.com/doc", null)).contains(id.toString());

        when(ingestionService.ingestUrl("not a url", null))
                .thenThrow(new IllegalArgumentException("Not an absolute URL: not a url"));
        assertThat(tools.ingestUrl("not a url", null)).startsWith("Error:");
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test "-Dtest=IngestionServiceFileTest,IngestionToolsTest"`
Expected: COMPILE FAIL — no `ingestUrl`.

- [ ] **Step 4: Implement in IngestionService**

Add imports `java.net.URI`, `org.springframework.core.io.ByteArrayResource`, `org.springframework.ai.reader.jsoup.JsoupDocumentReader`, then add after `ingestMarkdown`:

```java
    public DocumentSource ingestUrl(String url, String title) {
        URI uri = URI.create(url);
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Not an absolute URL: " + url);
        }
        byte[] content = fetch(uri);
        String checksum = sha256(content);
        String effectiveTitle = (title == null || title.isBlank()) ? url : title;
        return doIngest(url, "WEB", effectiveTitle, checksum,
                () -> new JsoupDocumentReader(new ByteArrayResource(content)).get());
    }

    private static byte[] fetch(URI uri) {
        try (var in = uri.toURL().openStream()) {
            return in.readAllBytes();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot fetch " + uri, e);
        }
    }
```

- [ ] **Step 5: Add the MCP tool in IngestionTools**

```java
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
```

- [ ] **Step 6: Run all tests**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/docmind/ src/test/java/com/docmind/
git commit -m "feat: web page ingestion via ingest_url"
```

---

### Task 2: get_chunk_context (small-to-big retrieval)

**Files:**
- Modify: `src/main/java/com/docmind/search/SearchService.java` (add `chunkIndex` to results + context query)
- Modify: `src/main/java/com/docmind/mcp/SearchTools.java` (new tool method)
- Modify: `src/test/java/com/docmind/mcp/SearchToolsTest.java` (SearchResult now has 5 components)
- Test: `src/test/java/com/docmind/search/ChunkContextTest.java` (create)

**Interfaces:**
- Consumes: `vector_store` metadata (`doc_id`, `chunk_index`), `JdbcTemplate` (new constructor arg for `SearchService`).
- Produces: `SearchResult` becomes `SearchResult(String docId, String sourceUri, int chunkIndex, String content, Double score)` — **breaking change**: every construction/usage must be updated (`SearchToolsTest` expectations). New `List<ChunkContext> chunkContext(UUID docId, int chunkIndex, int window)` with `record ChunkContext(int chunkIndex, String content)`; MCP tool `get_chunk_context(docId, chunkIndex, window?)`.

- [ ] **Step 1: Write the failing test**

```java
package com.docmind.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionService;
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
class ChunkContextTest {

    @Autowired
    SearchService searchService;

    @Autowired
    IngestionService ingestionService;

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
    void returnsNeighboringChunksInOrder() throws Exception {
        // Long repetitive sections force the splitter to emit multiple chunks
        StringBuilder md = new StringBuilder();
        for (int section = 1; section <= 6; section++) {
            md.append("# Section ").append(section).append("\n\n");
            md.append(("Topic " + section + " sentence. ").repeat(400)).append("\n\n");
        }
        Path file = tempDir.resolve("long.md");
        Files.writeString(file, md.toString());
        DocumentSource doc = ingestionService.ingestFile(file);
        assertThat(doc.chunkCount()).isGreaterThanOrEqualTo(3);

        List<SearchService.ChunkContext> context =
                searchService.chunkContext(doc.id(), 1, 1);

        assertThat(context).extracting(SearchService.ChunkContext::chunkIndex)
                .containsExactly(0, 1, 2);
        assertThat(searchService.chunkContext(UUID.randomUUID(), 1, 1)).isEmpty();
    }

    @Test
    void searchResultsCarryChunkIndex() throws Exception {
        Path file = tempDir.resolve("kafka.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        ingestionService.ingestFile(file);

        assertThat(searchService.search("event streaming", 3).getFirst().chunkIndex())
                .isGreaterThanOrEqualTo(0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=ChunkContextTest`
Expected: COMPILE FAIL — no `chunkContext`, no `chunkIndex()` on `SearchResult`.

- [ ] **Step 3: Implement in SearchService**

Add constructor arg + field `private final JdbcTemplate jdbcTemplate;` (import `org.springframework.jdbc.core.JdbcTemplate`, `java.util.UUID`), extend the mapping, and add:

```java
    public SearchService(VectorStore vectorStore, com.docmind.config.DocmindProperties properties,
                         JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = properties.similarityThreshold();
        this.jdbcTemplate = jdbcTemplate;
    }
```

In the `search` result mapping, replace the `SearchResult` construction with:

```java
                .map(doc -> new SearchResult(
                        (String) doc.getMetadata().get("doc_id"),
                        (String) doc.getMetadata().get("source_uri"),
                        ((Number) doc.getMetadata().get("chunk_index")).intValue(),
                        doc.getText(),
                        doc.getScore()))
```

Then add the context query and records:

```java
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
```

(Delete the old 4-component `SearchResult` declaration.)

- [ ] **Step 4: Fix the other SearchService consumers and add the tool**

In `SearchToolsTest`, the stub result becomes `new SearchService.SearchResult("id-1", "file:///a.md", 0, "content", 0.9)`.

In `SearchThresholdTest` (Milestone 5), both direct constructions gain the autowired `jdbcTemplate` as third argument, e.g. `new SearchService(vectorStore, new DocmindProperties(null, false, 0.0), jdbcTemplate)`.

In `SearchTools` add:

```java
    @McpTool(name = "get_chunk_context", description = """
            Fetch the chunks surrounding a search hit (same document, neighboring \
            chunk_index values) to get more context than a single chunk provides. \
            Use the docId and chunkIndex from a search_docs result.""")
    public java.util.List<com.docmind.search.SearchService.ChunkContext> getChunkContext(
            @McpToolParam(description = "Document id (UUID) from a search result") String docId,
            @McpToolParam(description = "chunk_index of the hit") int chunkIndex,
            @McpToolParam(description = "Neighbors on each side, 1-5 (default 1)", required = false) Integer window) {
        return searchService.chunkContext(java.util.UUID.fromString(docId), chunkIndex,
                window == null ? 1 : window);
    }
```

- [ ] **Step 5: Run all tests**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/docmind/ src/test/java/com/docmind/
git commit -m "feat: get_chunk_context neighboring-chunk retrieval"
```

---

### Task 3: Periodic re-scan of the docs folder

**Files:**
- Create: `src/main/java/com/docmind/ingestion/RescanScheduler.java`
- Modify: `src/main/java/com/docmind/DocmindApplication.java` (add `@EnableScheduling`)
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/docmind/ingestion/RescanSchedulerTest.java`

**Interfaces:**
- Consumes: `DocsFolderScanner.scan(Path)` (Milestone 3), `DocmindProperties.docsDir()`.
- Produces: when `docmind.rescan-enabled=true`, `scan` runs every `docmind.rescan-interval` (ISO-8601 duration, default `PT10M`). Checksum skip (Milestone 3) makes unchanged files free; changed files re-index automatically.

- [ ] **Step 1: Write the failing unit test**

```java
package com.docmind.ingestion;

import java.nio.file.Path;

import com.docmind.config.DocmindProperties;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RescanSchedulerTest {

    @Test
    void rescanDelegatesToScannerWithConfiguredFolder() {
        DocsFolderScanner scanner = mock(DocsFolderScanner.class);
        DocmindProperties properties = new DocmindProperties(Path.of("some-dir"), false, 0.0);

        new RescanScheduler(scanner, properties).rescan();

        verify(scanner).scan(Path.of("some-dir"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=RescanSchedulerTest`
Expected: COMPILE FAIL — no `RescanScheduler`.

- [ ] **Step 3: Implement**

```java
package com.docmind.ingestion;

import com.docmind.config.DocmindProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.rescan-enabled", havingValue = "true")
public class RescanScheduler {

    private final DocsFolderScanner scanner;
    private final DocmindProperties properties;

    public RescanScheduler(DocsFolderScanner scanner, DocmindProperties properties) {
        this.scanner = scanner;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${docmind.rescan-interval:PT10M}")
    public void rescan() {
        scanner.scan(properties.docsDir());
    }
}
```

Add `@EnableScheduling` (import `org.springframework.scheduling.annotation.EnableScheduling`) to `DocmindApplication`. `docmind.rescan-enabled` / `docmind.rescan-interval` are read via `@ConditionalOnProperty` and the placeholder — deliberately not added to the `DocmindProperties` record (unknown keys under the prefix are ignored by the binder).

In `application.yml` extend the `docmind:` block:

```yaml
  rescan-enabled: false
  rescan-interval: PT10M
```

- [ ] **Step 4: Run all tests + manual check**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green (rescan disabled by default).

Manual: start with `-Ddocmind.rescan-enabled=true -Ddocmind.rescan-interval=PT30S`, edit a file in `docs-inbox`, wait ~30s, watch the scan log line and verify `search_docs` returns the new content.

- [ ] **Step 5: Update README and commit**

Add to the ingestion paragraph: `Set docmind.rescan-enabled: true to re-scan the folder periodically (docmind.rescan-interval, default 10 minutes); tools list gains ingest_url and get_chunk_context.` Update the status line to Milestone 6.

```bash
git add src/main/java/com/docmind/ src/main/resources/application.yml src/test/java/com/docmind/ingestion/RescanSchedulerTest.java README.md
git commit -m "feat: periodic docs folder re-scan"
```

---

## Final verification

- [ ] `.\mvnw.cmd test` all green.
- [ ] Inspector or Claude: `ingest_url` a real page, search it, pull `get_chunk_context` around a hit.
- [ ] Finish with superpowers:finishing-a-development-branch.
