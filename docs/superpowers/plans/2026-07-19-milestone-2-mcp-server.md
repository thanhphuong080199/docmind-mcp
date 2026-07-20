# Milestone 2: First MCP Tool (Streamable-HTTP + search_docs) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Milestone 1 app into a real MCP server over Streamable-HTTP exposing `search_docs`, verified with MCP Inspector.

**Architecture:** Add `spring-ai-starter-mcp-server-webmvc` (+ `spring-boot-starter-web`, absent so far) and configure `protocol: STREAMABLE` at endpoint `/mcp`. A thin `com.docmind.mcp.SearchTools` component annotated with `@McpTool` delegates to `SearchService`; Spring AI's annotation scanner auto-registers it. `SearchService` gains an optional per-document filter (`docId`) using the `doc_id` chunk metadata written in Milestone 1.

**Tech Stack:** Spring Boot 4.1.0, Spring AI 2.0.0 (`spring-ai-starter-mcp-server-webmvc`), MCP Inspector (`npx @modelcontextprotocol/inspector`).

## Global Constraints

- Java 25; system `java` is still 17, so prefix every Maven call: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd ...` (PowerShell). No global Maven — always the wrapper.
- All tests need docker compose services up (`docker compose up -d`) with `nomic-embed-text` pulled into the `docmind-ollama` container. If image pulls fail with CloudFront EOF, use the `mirror.gcr.io` retag workaround in README.
- Spring AI BOM 2.0.0 manages all `org.springframework.ai` versions — never pin them in `<dependencies>`.
- Chunk metadata keys are exactly `doc_id`, `source_uri`, `chunk_index` (later milestones depend on them).
- MCP transport is Streamable-HTTP (`spring.ai.mcp.server.protocol=STREAMABLE`), stateful, endpoint `/mcp`. SSE is deprecated in Spring AI 2.0 — do not use it.
- MCP layer stays thin: no business logic in `com.docmind.mcp`, only delegation and input normalization.
- Work on branch `milestone-2-mcp-server` cut from `main` (after PR #1 is merged).

---

### Task 1: MCP server starter, configuration, and handshake smoke test

**Files:**
- Modify: `pom.xml` (dependencies block)
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/docmind/mcp/McpEndpointSmokeTest.java`

**Interfaces:**
- Consumes: nothing new — existing app context.
- Produces: a running MCP endpoint `POST /mcp` (Streamable-HTTP, stateful) with server name `docmind`, version `0.1.0`. Task 3's tool registration and Task 4's Inspector session depend on it.

- [ ] **Step 1: Write the failing smoke test**

Note: Boot 4 removed `TestRestTemplate` from `spring-boot-starter-test` (test-support modularization), so this test uses plain JDK `HttpClient` with the injected random port instead.

```java
package com.docmind.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointSmokeTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void initializeHandshakeReturnsServerInfo() throws Exception {
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"smoke-test","version":"0.0.1"}}}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                // Streamable-HTTP spec: client must accept both JSON and SSE
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initialize))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("docmind");
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=McpEndpointSmokeTest`
Expected: FAIL — either context error (no web server: `webEnvironment` needs servlet) or 404 from `/mcp`.

- [ ] **Step 3: Add dependencies**

In `pom.xml`, inside `<dependencies>` (order does not matter):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
        </dependency>
```

`spring-boot-starter-web` is required — Milestone 1 had no HTTP layer at all.

- [ ] **Step 4: Configure the MCP server**

In `src/main/resources/application.yml`, add under the existing `spring.ai:` key (sibling of `model:`, `ollama:`, `vectorstore:`):

```yaml
    mcp:
      server:
        name: docmind
        version: 0.1.0
        protocol: STREAMABLE
        instructions: "Semantic search over locally ingested technical documentation. Use search_docs to ground answers in the user's own docs."
        streamable-http:
          mcp-endpoint: /mcp
```

(`/mcp` is the default endpoint; set it explicitly so the contract is visible.)

- [ ] **Step 5: Run test to verify it passes**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=McpEndpointSmokeTest`
Expected: PASS

- [ ] **Step 6: Run the full suite (regression)**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green (4 tests).

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yml src/test/java/com/docmind/mcp/McpEndpointSmokeTest.java
git commit -m "feat: add MCP server over Streamable-HTTP at /mcp"
```

---

### Task 2: Optional per-document filter in SearchService

**Files:**
- Modify: `src/main/java/com/docmind/search/SearchService.java`
- Test: `src/test/java/com/docmind/IngestAndSearchIntegrationTest.java` (add one test)

**Interfaces:**
- Consumes: chunk metadata key `doc_id` (stamped by `IngestionService` in Milestone 1).
- Produces: `List<SearchResult> search(String query, int topK, String docId)` — `docId` nullable/blank means "no filter". Existing `search(String query, int topK)` keeps working. Task 3's `SearchTools.searchDocs` calls the 3-arg overload.

- [ ] **Step 1: Write the failing test**

Add to `IngestAndSearchIntegrationTest` (uses the existing `@BeforeEach cleanStore()`):

```java
    @Test
    void searchWithDocIdFilterOnlyReturnsThatDocument() {
        DocumentSource doc = ingestionService.ingestMarkdown(
                new ClassPathResource("samples/spring-boot-overview.md"),
                "Spring Boot Overview");

        List<SearchService.SearchResult> filtered =
                searchService.search("auto-configuration", 5, doc.id().toString());
        List<SearchService.SearchResult> noMatch =
                searchService.search("auto-configuration", 5, java.util.UUID.randomUUID().toString());

        assertThat(filtered).isNotEmpty();
        assertThat(filtered).allMatch(r -> r.docId().equals(doc.id().toString()));
        assertThat(noMatch).isEmpty();
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestAndSearchIntegrationTest`
Expected: COMPILE FAIL — no 3-arg `search` method.

- [ ] **Step 3: Implement the overload**

Replace the body of `SearchService` with:

```java
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=IngestAndSearchIntegrationTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/search/SearchService.java src/test/java/com/docmind/IngestAndSearchIntegrationTest.java
git commit -m "feat: optional doc_id filter in SearchService"
```

---

### Task 3: search_docs MCP tool

**Files:**
- Create: `src/main/java/com/docmind/mcp/SearchTools.java`
- Test: `src/test/java/com/docmind/mcp/SearchToolsTest.java`

**Interfaces:**
- Consumes: `SearchService.search(String query, int topK, String docId)` and `SearchService.SearchResult(String docId, String sourceUri, String content, Double score)` from Task 2.
- Produces: MCP tool `search_docs(query, topK?, docId?)` returning `List<SearchService.SearchResult>`. Registered automatically because `SearchTools` is a `@Component` with `@McpTool` methods (Spring AI annotation scanner). Milestone 5 will tune the description text — keep the name stable.

- [ ] **Step 1: Write the failing unit test**

```java
package com.docmind.mcp;

import java.util.List;

import com.docmind.search.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchToolsTest {

    @Mock
    SearchService searchService;

    @InjectMocks
    SearchTools searchTools;

    @Test
    void delegatesWithDefaults() {
        List<SearchService.SearchResult> expected = List.of(
                new SearchService.SearchResult("id-1", "file:///a.md", "content", 0.9));
        when(searchService.search("query", 5, null)).thenReturn(expected);

        assertThat(searchTools.searchDocs("query", null, null)).isEqualTo(expected);
    }

    @Test
    void clampsTopKBetween1And20() {
        searchTools.searchDocs("q", 100, null);
        verify(searchService).search(eq("q"), eq(20), eq(null));

        searchTools.searchDocs("q", 0, "some-doc");
        verify(searchService).search(eq("q"), eq(5), eq("some-doc"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=SearchToolsTest`
Expected: COMPILE FAIL — `SearchTools` does not exist.

- [ ] **Step 3: Implement SearchTools**

```java
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
```

- [ ] **Step 4: Run all tests**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/mcp/SearchTools.java src/test/java/com/docmind/mcp/SearchToolsTest.java
git commit -m "feat: expose search_docs MCP tool"
```

---

### Task 4: Verify with MCP Inspector and document

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: running server from Task 1 + tool from Task 3.
- Produces: human-verified MCP session; README section other clients follow in Milestone 5.

- [ ] **Step 1: Start the stack and the server**

```powershell
docker compose up -d
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=demo"
```

The `demo` profile ingests the sample doc, so `search_docs` has data.

- [ ] **Step 2: Run MCP Inspector (needs Node.js)**

```powershell
npx @modelcontextprotocol/inspector
```

In the browser UI: Transport `Streamable HTTP`, URL `http://localhost:8080/mcp`, Connect.
Expected: server info shows `docmind 0.1.0`; Tools tab lists `search_docs`.

- [ ] **Step 3: Call the tool in Inspector**

Call `search_docs` with `query = "How does auto-configuration work?"`, leave `topK`/`docId` empty.
Expected: JSON result whose first entry's `content` covers auto-configuration, `score` ≈ 0.7+.

- [ ] **Step 4: Update README**

Change the status line near the top to `Work in progress — currently at Milestone 2 (MCP server with search_docs).` and add after the Quickstart section:

````markdown
## Using the MCP server

Start the app, then connect any MCP client to `http://localhost:8080/mcp`
(Streamable-HTTP). Quick check with MCP Inspector:

```bash
npx @modelcontextprotocol/inspector
# Transport: Streamable HTTP, URL: http://localhost:8080/mcp
```

Available tools: `search_docs(query, topK?, docId?)`.
````

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: MCP Inspector usage for search_docs"
```

---

## Final verification

- [ ] `.\mvnw.cmd test` all green (JAVA_HOME prefix).
- [ ] MCP Inspector connects and `search_docs` returns scored chunks.
- [ ] Finish with superpowers:finishing-a-development-branch (push + PR, as with Milestone 1).
