# Milestone 7 — Confluence Cloud Ingestion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Confluence Cloud pages searchable through docmind by pulling them via the v2 REST API, indexing them in pgvector, and keeping them fresh with checksum-cheap re-syncs — all through the existing unified `search_docs`.

**Architecture:** A new `com.docmind.confluence` package holds a thin `ConfluenceClient` (Spring `RestClient`, Basic auth, no ingestion knowledge), a `ConfluenceSyncService` (enumerate → ingest → stale-remove, no HTTP knowledge), and an optional `ConfluenceSyncScheduler`. `IngestionService` gains one public method `ingestConfluencePage` that routes through the existing private `doIngest` with a `JsoupDocumentReader` over the page's storage-XHTML body. A thin `ConfluenceTools` exposes `sync_confluence` and `ingest_confluence_page`. Every Confluence bean is guarded by `@ConditionalOnProperty(name = "docmind.confluence.base-url")`, so an unconfigured docmind exposes nothing and errors nowhere.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, Spring `RestClient` + `MockRestServiceServer` (no new dependency), pgvector, JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Java 25, Spring Boot **4.1.0**, Spring AI **2.0.0** — no version changes.
- **No new Maven dependency.** `spring-ai-jsoup-document-reader` (pom.xml:66-69) and `spring-boot-starter-web` (RestClient) and `spring-boot-starter-test` (MockRestServiceServer) are already present.
- MCP layer stays **thin**: tools only format results and translate exceptions to strings; all logic lives in services.
- Tool errors are **structured strings** (`"Error: …"`), never stack traces.
- Every Confluence bean is annotated `@ConditionalOnProperty(name = "docmind.confluence.base-url")`.
- The API token is read **only** from an environment variable (`CONFLUENCE_API_TOKEN`); never commit it.
- **Cloud only** (`*.atlassian.net`, v2 API). No Data Center / v1 / Bearer PAT.
- `doc_type` for Confluence documents = the exact string `"CONFLUENCE"`.
- `source_uri` = `{base-url}/wiki/spaces/{KEY}/pages/{pageId}` (no title slug; `base-url` has no trailing `/wiki`).
- Checksum = SHA-256 over the UTF-8 bytes of `title + "\n" + body`.
- Stale removal runs **only after a complete enumeration** — a partial/aborted sync never deletes documents.

---

## File Structure

**New production files**
- `src/main/java/com/docmind/confluence/ConfluencePage.java` — DTO record: `id, spaceKey, title, body, webUrl`.
- `src/main/java/com/docmind/confluence/ConfluenceException.java` — `RuntimeException` for clear auth / not-found / network messages.
- `src/main/java/com/docmind/confluence/ConfluenceClient.java` — v2 REST over `RestClient`, Basic auth, cursor pagination, DTO mapping.
- `src/main/java/com/docmind/confluence/ConfluenceSyncService.java` — per-space enumerate/ingest/stale-remove + single-page ingest; `SyncResult` record.
- `src/main/java/com/docmind/confluence/ConfluenceSyncScheduler.java` — optional periodic re-sync (mirrors `RescanScheduler`).
- `src/main/java/com/docmind/mcp/ConfluenceTools.java` — `sync_confluence`, `ingest_confluence_page`.

**New test files**
- `src/test/java/com/docmind/confluence/ConfluenceClientTest.java` — `MockRestServiceServer`.
- `src/test/java/com/docmind/confluence/ConfluenceSyncServiceTest.java` — `@SpringBootTest` + `@MockitoBean ConfluenceClient` (needs `docker compose up -d`).
- `src/test/java/com/docmind/confluence/ConfluenceSyncSchedulerTest.java` — plain Mockito.
- `src/test/java/com/docmind/mcp/ConfluenceToolsTest.java` — plain Mockito.
- `src/test/java/com/docmind/ingestion/IngestionServiceConfluenceTest.java` — `@SpringBootTest` (needs `docker compose up -d`).

**Modified files**
- `src/main/java/com/docmind/config/DocmindProperties.java` — add nested `Confluence` record component (breaks 3 constructor call-sites, fixed in Task 1).
- `src/main/java/com/docmind/ingestion/IngestionService.java` — add `ingestConfluencePage` + internal `IngestOutcome` refactor of `doIngest`.
- `src/main/java/com/docmind/domain/DocumentSourceRepository.java` — add `findByDocTypeAndSourceUriStartingWith`.
- `src/main/resources/application.yml` — add commented `docmind.confluence` block.
- `README.md` — document config, `CONFLUENCE_API_TOKEN`, and the two tools.

---

## Task 1: Configuration — nested `Confluence` record

**Files:**
- Modify: `src/main/java/com/docmind/config/DocmindProperties.java`
- Modify: `src/test/java/com/docmind/search/SearchThresholdTest.java:49,51`
- Modify: `src/test/java/com/docmind/ingestion/RescanSchedulerTest.java:16`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/docmind/config/DocmindPropertiesTest.java`

**Interfaces:**
- Produces: `DocmindProperties.Confluence(String baseUrl, String email, String apiToken, List<String> spaceKeys, boolean syncEnabled, String syncInterval)`, accessible via `properties.confluence()`. `spaceKeys()` is never null (defaults to `List.of()`). `DocmindProperties` now has a **4th** constructor component `Confluence confluence` (may be null when the confluence block is absent).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/docmind/config/DocmindPropertiesTest.java`:

```java
package com.docmind.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocmindPropertiesTest {

    @Test
    void confluenceSpaceKeysDefaultsToEmptyWhenNull() {
        var confluence = new DocmindProperties.Confluence(
                "https://c.atlassian.net", "me@c.com", "tok", null, false, "PT1H");
        assertThat(confluence.spaceKeys()).isEmpty();
    }

    @Test
    void confluenceMayBeNullOnRoot() {
        var props = new DocmindProperties(null, false, 0.0, null);
        assertThat(props.confluence()).isNull();
        assertThat(props.docsDir()).isNotNull();
    }

    @Test
    void confluenceRetainsProvidedValues() {
        var props = new DocmindProperties(null, false, 0.0,
                new DocmindProperties.Confluence(
                        "https://c.atlassian.net", "me@c.com", "tok", List.of("DOCS", "ENG"), true, "PT2H"));
        assertThat(props.confluence().baseUrl()).isEqualTo("https://c.atlassian.net");
        assertThat(props.confluence().spaceKeys()).containsExactly("DOCS", "ENG");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw test -Dtest=DocmindPropertiesTest`
Expected: COMPILE FAILURE — `DocmindProperties.Confluence` does not exist and the 4-arg constructor does not exist.

- [ ] **Step 3: Add the nested record and 4th component**

Replace the whole body of `src/main/java/com/docmind/config/DocmindProperties.java`:

```java
package com.docmind.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docmind")
public record DocmindProperties(Path docsDir, boolean scanOnStartup, double similarityThreshold,
                                Confluence confluence) {

    public DocmindProperties {
        if (docsDir == null) {
            docsDir = Path.of("docs-inbox");
        }
    }

    public record Confluence(String baseUrl, String email, String apiToken,
                             List<String> spaceKeys, boolean syncEnabled, String syncInterval) {

        public Confluence {
            if (spaceKeys == null) {
                spaceKeys = List.of();
            }
        }
    }
}
```

- [ ] **Step 4: Fix the 3 broken constructor call-sites**

In `src/test/java/com/docmind/search/SearchThresholdTest.java`, change the two constructions (lines 49 and 51) to pass a 4th `null` argument:

```java
        SearchService permissive = new SearchService(vectorStore,
                new DocmindProperties(null, false, 0.0, null), jdbcTemplate);
        SearchService strict = new SearchService(vectorStore,
                new DocmindProperties(null, false, 0.99, null), jdbcTemplate);
```

In `src/test/java/com/docmind/ingestion/RescanSchedulerTest.java`, change line 16:

```java
        DocmindProperties properties = new DocmindProperties(Path.of("some-dir"), false, 0.0, null);
```

- [ ] **Step 5: Add the commented config block**

In `src/main/resources/application.yml`, under the existing `docmind:` block (after `rescan-interval: PT10M`), add:

```yaml
  # Confluence Cloud ingestion (Milestone 7). Leave base-url unset to disable entirely.
  # confluence:
  #   base-url: https://yourcompany.atlassian.net   # no trailing /wiki
  #   email: you@company.com
  #   api-token: ${CONFLUENCE_API_TOKEN}            # env var only — never commit
  #   space-keys: [DOCS, ENG]
  #   sync-enabled: false
  #   sync-interval: PT1H
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `.\mvnw test -Dtest=DocmindPropertiesTest`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/docmind/config/DocmindProperties.java src/test/java/com/docmind/config/DocmindPropertiesTest.java src/test/java/com/docmind/search/SearchThresholdTest.java src/test/java/com/docmind/ingestion/RescanSchedulerTest.java src/main/resources/application.yml
git commit -m "feat: nested Confluence configuration properties"
```

---

## Task 2: `ConfluenceClient` + `ConfluencePage` + `ConfluenceException`

**Files:**
- Create: `src/main/java/com/docmind/confluence/ConfluencePage.java`
- Create: `src/main/java/com/docmind/confluence/ConfluenceException.java`
- Create: `src/main/java/com/docmind/confluence/ConfluenceClient.java`
- Test: `src/test/java/com/docmind/confluence/ConfluenceClientTest.java`

**Interfaces:**
- Produces:
  - `ConfluencePage(String id, String spaceKey, String title, String body, String webUrl)` — `body` is storage-format XHTML.
  - `ConfluenceException extends RuntimeException` (message-only + message+cause constructors).
  - `ConfluenceClient(RestClient.Builder builder, DocmindProperties properties)` — a Spring `@Component`, `@ConditionalOnProperty(name = "docmind.confluence.base-url")`.
  - `String ConfluenceClient.spaceId(String spaceKey)` — throws `ConfluenceException("Confluence space not found: KEY")` if it does not resolve; `ConfluenceException` with an auth hint on 401/403.
  - `List<ConfluencePage> ConfluenceClient.pages(String spaceId)` — follows `_links.next` cursor pagination, returns every page with storage body.
  - `ConfluencePage ConfluenceClient.page(String pageId)` — throws `ConfluenceException("Confluence page not found: id")` on 404.
- Consumes: `DocmindProperties.Confluence` from Task 1.

**Design notes for the implementer:**
- v2 JSON shapes this client maps (author-controlled in the test):
  - `GET {base}/wiki/api/v2/spaces?keys=KEY` → `{ "results": [ { "id": "111", "key": "DOCS" } ] }`
  - `GET {base}/wiki/api/v2/spaces/{id}/pages?body-format=storage&limit=50` → `{ "results": [ { "id": "456", "title": "T", "body": { "storage": { "value": "<p>x</p>" } }, "_links": { "webui": "/spaces/DOCS/pages/456/T" } } ], "_links": { "next": "/wiki/api/v2/spaces/111/pages?limit=50&cursor=ABC" } }` — `_links.next` absent/null on the last page.
  - `GET {base}/wiki/api/v2/pages/{id}?body-format=storage` → single page object (same shape as a `results[]` element).
- `spaceKey` is derived from `_links.webui` (`/spaces/{KEY}/pages/...`) so both `pages` and `page` populate it without an extra call.
- `webUrl` = `baseUrl + "/wiki" + webui` (full clickable URL, with slug).
- `RestClient.Builder` is auto-configured by `spring-boot-starter-web`; the client sets `baseUrl(cfg.baseUrl())` and a default `Authorization: Basic` header via `headers.setBasicAuth(email, apiToken)`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/docmind/confluence/ConfluenceClientTest.java`:

```java
package com.docmind.confluence;

import java.util.Base64;
import java.util.List;

import com.docmind.config.DocmindProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConfluenceClientTest {

    private static final String BASE = "https://c.atlassian.net";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private ConfluenceClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new DocmindProperties(null, false, 0.0,
                new DocmindProperties.Confluence(BASE, "me@c.com", "tok", List.of("DOCS"), false, "PT1H"));
        client = new ConfluenceClient(builder, props);
    }

    @Test
    void spaceIdSendsBasicAuthAndResolvesId() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("me@c.com:tok".getBytes());
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces?keys=DOCS"))
                .andExpect(header("Authorization", basic))
                .andRespond(withSuccess("{\"results\":[{\"id\":\"111\",\"key\":\"DOCS\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.spaceId("DOCS")).isEqualTo("111");
        server.verify();
    }

    @Test
    void unknownSpaceThrowsClearError() {
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces?keys=NOPE"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.spaceId("NOPE"))
                .isInstanceOf(ConfluenceException.class)
                .hasMessageContaining("NOPE");
    }

    @Test
    void unauthorizedSurfacedAsClearError() {
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces?keys=DOCS"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.spaceId("DOCS"))
                .isInstanceOf(ConfluenceException.class)
                .hasMessageContaining("CONFLUENCE_API_TOKEN");
    }

    @Test
    void pagesFollowsCursorAndMapsDtos() {
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces/111/pages?body-format=storage&limit=50"))
                .andRespond(withSuccess("""
                        {"results":[{"id":"456","title":"First",
                          "body":{"storage":{"value":"<p>one</p>"}},
                          "_links":{"webui":"/spaces/DOCS/pages/456/First"}}],
                         "_links":{"next":"/wiki/api/v2/spaces/111/pages?limit=50&cursor=ABC"}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/wiki/api/v2/spaces/111/pages?limit=50&cursor=ABC"))
                .andRespond(withSuccess("""
                        {"results":[{"id":"789","title":"Second",
                          "body":{"storage":{"value":"<p>two</p>"}},
                          "_links":{"webui":"/spaces/DOCS/pages/789/Second"}}],
                         "_links":{}}""",
                        MediaType.APPLICATION_JSON));

        List<ConfluencePage> pages = client.pages("111");

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).id()).isEqualTo("456");
        assertThat(pages.get(0).spaceKey()).isEqualTo("DOCS");
        assertThat(pages.get(0).title()).isEqualTo("First");
        assertThat(pages.get(0).body()).isEqualTo("<p>one</p>");
        assertThat(pages.get(0).webUrl()).isEqualTo(BASE + "/wiki/spaces/DOCS/pages/456/First");
        assertThat(pages.get(1).id()).isEqualTo("789");
        server.verify();
    }

    @Test
    void pageFetchesSingleAndMaps() {
        server.expect(requestTo(BASE + "/wiki/api/v2/pages/456?body-format=storage"))
                .andRespond(withSuccess("""
                        {"id":"456","title":"First",
                         "body":{"storage":{"value":"<p>one</p>"}},
                         "_links":{"webui":"/spaces/DOCS/pages/456/First"}}""",
                        MediaType.APPLICATION_JSON));

        ConfluencePage page = client.page("456");

        assertThat(page.spaceKey()).isEqualTo("DOCS");
        assertThat(page.body()).isEqualTo("<p>one</p>");
        server.verify();
    }

    @Test
    void missingPageThrowsClearError() {
        server.expect(requestTo(BASE + "/wiki/api/v2/pages/999?body-format=storage"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.page("999"))
                .isInstanceOf(ConfluenceException.class)
                .hasMessageContaining("999");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw test -Dtest=ConfluenceClientTest`
Expected: COMPILE FAILURE — `ConfluenceClient`, `ConfluencePage`, `ConfluenceException` do not exist.

- [ ] **Step 3: Create `ConfluencePage`**

Create `src/main/java/com/docmind/confluence/ConfluencePage.java`:

```java
package com.docmind.confluence;

/**
 * A Confluence page fetched from the v2 REST API.
 *
 * @param body storage-format XHTML (parsed as-is by JsoupDocumentReader)
 * @param webUrl full clickable browser URL (with title slug)
 */
public record ConfluencePage(String id, String spaceKey, String title, String body, String webUrl) {
}
```

- [ ] **Step 4: Create `ConfluenceException`**

Create `src/main/java/com/docmind/confluence/ConfluenceException.java`:

```java
package com.docmind.confluence;

public class ConfluenceException extends RuntimeException {

    public ConfluenceException(String message) {
        super(message);
    }

    public ConfluenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Create `ConfluenceClient`**

Create `src/main/java/com/docmind/confluence/ConfluenceClient.java`:

```java
package com.docmind.confluence;

import java.util.ArrayList;
import java.util.List;

import com.docmind.config.DocmindProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "docmind.confluence.base-url")
public class ConfluenceClient {

    private final RestClient restClient;
    private final String baseUrl;

    public ConfluenceClient(RestClient.Builder builder, DocmindProperties properties) {
        DocmindProperties.Confluence cfg = properties.confluence();
        this.baseUrl = cfg.baseUrl();
        this.restClient = builder
                .baseUrl(cfg.baseUrl())
                .defaultHeaders(h -> h.setBasicAuth(cfg.email(), cfg.apiToken()))
                .build();
    }

    public String spaceId(String spaceKey) {
        SpacesResponse response = restClient.get()
                .uri("/wiki/api/v2/spaces?keys={key}", spaceKey)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> { throw authOrGeneric(resp.getStatusCode()); })
                .body(SpacesResponse.class);
        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new ConfluenceException("Confluence space not found: " + spaceKey);
        }
        return response.results().get(0).id();
    }

    public List<ConfluencePage> pages(String spaceId) {
        List<ConfluencePage> all = new ArrayList<>();
        String uri = "/wiki/api/v2/spaces/" + spaceId + "/pages?body-format=storage&limit=50";
        while (uri != null) {
            PagesResponse response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, resp) -> { throw authOrGeneric(resp.getStatusCode()); })
                    .body(PagesResponse.class);
            if (response == null) {
                break;
            }
            for (PageJson p : response.results()) {
                all.add(toPage(p));
            }
            uri = (response.links() == null) ? null : response.links().next();
        }
        return all;
    }

    public ConfluencePage page(String pageId) {
        PageJson json = restClient.get()
                .uri("/wiki/api/v2/pages/{id}?body-format=storage", pageId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, resp) -> {
                    if (resp.getStatusCode().value() == 404) {
                        throw new ConfluenceException("Confluence page not found: " + pageId);
                    }
                    throw authOrGeneric(resp.getStatusCode());
                })
                .body(PageJson.class);
        if (json == null) {
            throw new ConfluenceException("Confluence page not found: " + pageId);
        }
        return toPage(json);
    }

    private ConfluencePage toPage(PageJson p) {
        String webui = (p.links() == null) ? "" : p.links().webui();
        String spaceKey = spaceKeyFromWebui(webui);
        String body = (p.body() == null || p.body().storage() == null) ? "" : p.body().storage().value();
        return new ConfluencePage(p.id(), spaceKey, p.title(), body, baseUrl + "/wiki" + webui);
    }

    private static String spaceKeyFromWebui(String webui) {
        // webui is like "/spaces/DOCS/pages/456/Title-Slug"
        String[] parts = webui.split("/");
        if (parts.length >= 3 && "spaces".equals(parts[1])) {
            return parts[2];
        }
        throw new ConfluenceException("Cannot determine space key from webui link: " + webui);
    }

    private static ConfluenceException authOrGeneric(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return new ConfluenceException(
                    "Confluence auth failed (HTTP " + code + ") — check CONFLUENCE_API_TOKEN / email");
        }
        return new ConfluenceException("Confluence request failed (HTTP " + code + ")");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpacesResponse(List<SpaceRef> results) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpaceRef(String id, String key) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PagesResponse(List<PageJson> results, @JsonProperty("_links") Links links) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PageJson(String id, String title, Body body, @JsonProperty("_links") Links links) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Body(Storage storage) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Storage(String value) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Links(String next, String webui) { }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `.\mvnw test -Dtest=ConfluenceClientTest`
Expected: PASS (6 tests). No network, no Docker.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/docmind/confluence/ConfluencePage.java src/main/java/com/docmind/confluence/ConfluenceException.java src/main/java/com/docmind/confluence/ConfluenceClient.java src/test/java/com/docmind/confluence/ConfluenceClientTest.java
git commit -m "feat: ConfluenceClient over v2 REST API with cursor pagination"
```

---

## Task 3: `IngestionService.ingestConfluencePage` (+ `IngestOutcome` refactor)

**Files:**
- Modify: `src/main/java/com/docmind/ingestion/IngestionService.java`
- Test: `src/test/java/com/docmind/ingestion/IngestionServiceConfluenceTest.java`

**Interfaces:**
- Produces:
  - `IngestionService.IngestOutcome(DocumentSource document, boolean skipped)` — nested public record. `skipped` is `true` when the checksum matched an already-`INGESTED` row (no-op).
  - `IngestOutcome IngestionService.ingestConfluencePage(String sourceUri, String title, String storageXhtml)` — routes through the existing private `doIngest` with `doc_type = "CONFLUENCE"` and a `JsoupDocumentReader` over `storageXhtml`; checksum = SHA-256 of `title + "\n" + storageXhtml`.
- The private `doIngest` now returns `IngestOutcome`; the three existing public methods (`ingestFile`, `ingestMarkdown`, `ingestUrl`) unwrap via `.document()` and keep returning `DocumentSource` (no external caller changes).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/docmind/ingestion/IngestionServiceConfluenceTest.java`:

```java
package com.docmind.ingestion;

import com.docmind.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class IngestionServiceConfluenceTest {

    private static final String URI = "https://c.atlassian.net/wiki/spaces/DOCS/pages/456";

    @Autowired
    IngestionService ingestionService;

    @Autowired
    SearchService searchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanStore() {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE document_source");
    }

    @Test
    void ingestsConfluencePageWithCorrectTypeAndIsSearchable() {
        IngestionService.IngestOutcome outcome = ingestionService.ingestConfluencePage(
                URI, "Deploy Guide",
                "<h1>Deploy Guide</h1><p>Run kubectl apply to deploy the service.</p>");

        assertThat(outcome.skipped()).isFalse();
        assertThat(outcome.document().docType()).isEqualTo("CONFLUENCE");
        assertThat(outcome.document().chunkCount()).isGreaterThan(0);

        var results = searchService.search("how do I deploy the service?", 3);
        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().docId()).isEqualTo(outcome.document().id().toString());
    }

    @Test
    void reingestingUnchangedPageIsSkipped() {
        String body = "<p>unchanged content</p>";
        ingestionService.ingestConfluencePage(URI, "Title", body);

        IngestionService.IngestOutcome second = ingestionService.ingestConfluencePage(URI, "Title", body);

        assertThat(second.skipped()).isTrue();
    }

    @Test
    void renameChangesChecksumAndReingests() {
        String body = "<p>same body</p>";
        ingestionService.ingestConfluencePage(URI, "Old Title", body);

        IngestionService.IngestOutcome renamed = ingestionService.ingestConfluencePage(URI, "New Title", body);

        assertThat(renamed.skipped()).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw test -Dtest=IngestionServiceConfluenceTest`
Expected: COMPILE FAILURE — `ingestConfluencePage` and `IngestOutcome` do not exist. (Requires `docker compose up -d` to run once it compiles.)

- [ ] **Step 3: Refactor `doIngest` to return `IngestOutcome` and add `ingestConfluencePage`**

In `src/main/java/com/docmind/ingestion/IngestionService.java`:

Add the import near the other `java.nio` imports:

```java
import java.nio.charset.StandardCharsets;
```

Change the three public methods to unwrap `.document()`:

```java
    public DocumentSource ingestFile(Path path, String title) {
        Path normalized = path.toAbsolutePath().normalize();
        String sourceUri = normalized.toUri().toString();
        String docType = docTypeOf(normalized);
        String checksum = sha256(readBytes(normalized));
        Resource resource = new FileSystemResource(normalized);
        return doIngest(sourceUri, docType, title, checksum, () -> readDocuments(docType, resource)).document();
    }

    public DocumentSource ingestMarkdown(Resource resource, String title) {
        String sourceUri = sourceUriOf(resource);
        String checksum = sha256(contentOf(resource));
        return doIngest(sourceUri, "MARKDOWN", title, checksum, () -> readDocuments("MARKDOWN", resource)).document();
    }

    public DocumentSource ingestUrl(String url, String title) {
        URI uri = URI.create(url);
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Not an absolute URL: " + url);
        }
        byte[] content = fetch(uri);
        String checksum = sha256(content);
        String effectiveTitle = (title == null || title.isBlank()) ? url : title;
        return doIngest(url, "WEB", effectiveTitle, checksum,
                () -> new JsoupDocumentReader(new ByteArrayResource(content)).get()).document();
    }
```

Add the new public method (place it after `ingestUrl`):

```java
    public IngestOutcome ingestConfluencePage(String sourceUri, String title, String storageXhtml) {
        byte[] body = storageXhtml.getBytes(StandardCharsets.UTF_8);
        String checksum = sha256((title + "\n" + storageXhtml).getBytes(StandardCharsets.UTF_8));
        return doIngest(sourceUri, "CONFLUENCE", title, checksum,
                () -> new JsoupDocumentReader(new ByteArrayResource(body)).get());
    }
```

Change `doIngest`'s signature and both return points:

```java
    private IngestOutcome doIngest(String sourceUri, String docType, String title,
                                   String checksum, Supplier<List<Document>> readDocs) {
        var existing = repository.findBySourceUri(sourceUri);
        if (existing.isPresent()
                && checksum.equals(existing.get().checksum())
                && "INGESTED".equals(existing.get().status())) {
            return new IngestOutcome(existing.get(), true);
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

            return new IngestOutcome(aggregateTemplate.insert(new DocumentSource(
                    docId, title, sourceUri, docType, checksum,
                    chunks.size(), null, "INGESTED", Instant.now())), false);
        }
        catch (RuntimeException e) {
            aggregateTemplate.insert(new DocumentSource(
                    docId, title, sourceUri, docType, checksum,
                    0, null, "FAILED", Instant.now()));
            throw new IngestionException("Failed to ingest " + sourceUri + ": " + e.getMessage(), e);
        }
    }
```

Add the nested record (place it just before the closing brace of the class, after the last private helper):

```java
    public record IngestOutcome(DocumentSource document, boolean skipped) {
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run (with `docker compose up -d` already running): `.\mvnw test -Dtest=IngestionServiceConfluenceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the existing ingestion suite to confirm no regression**

Run: `.\mvnw test -Dtest=IngestionToolsTest,IngestAndSearchIntegrationTest`
Expected: PASS (existing tests unaffected by the internal `.document()` unwrap).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/docmind/ingestion/IngestionService.java src/test/java/com/docmind/ingestion/IngestionServiceConfluenceTest.java
git commit -m "feat: IngestionService.ingestConfluencePage with skip outcome"
```

---

## Task 4: `ConfluenceSyncService` (+ repository stale-lookup)

**Files:**
- Modify: `src/main/java/com/docmind/domain/DocumentSourceRepository.java`
- Create: `src/main/java/com/docmind/confluence/ConfluenceSyncService.java`
- Test: `src/test/java/com/docmind/confluence/ConfluenceSyncServiceTest.java`

**Interfaces:**
- Produces:
  - `DocumentSourceRepository.findByDocTypeAndSourceUriStartingWith(String docType, String prefix)` → `List<DocumentSource>`.
  - `ConfluenceSyncService(ConfluenceClient client, IngestionService ingestionService, DocumentSourceRepository repository, DocmindProperties properties)` — `@Service`, `@ConditionalOnProperty(name = "docmind.confluence.base-url")`.
  - `ConfluenceSyncService.SyncResult(String spaceKey, int ingested, int skipped, int failed, int removed)` — nested public record.
  - `SyncResult sync(String spaceKey)` — enumerate → ingest each → remove stale → counts. Throws `ConfluenceException` on auth/space-not-found/network (partial sync never removes).
  - `DocumentSource ingestPage(String pageId)` — fetch one page, ingest, return the stored `DocumentSource` (no stale removal).
  - `List<String> configuredSpaceKeys()` — convenience accessor for `properties.confluence().spaceKeys()`.
- Consumes: `ConfluenceClient` (Task 2), `IngestionService.ingestConfluencePage` + `IngestOutcome` (Task 3).

**Design notes:**
- `sourceUri(page)` = `baseUrl + "/wiki/spaces/" + page.spaceKey() + "/pages/" + page.id()`.
- Stale prefix for space `KEY` = `baseUrl + "/wiki/spaces/" + KEY + "/pages/"`.
- Per-page failure: catch `RuntimeException` from `ingestConfluencePage`, increment `failed`, continue (the `FAILED` row is recorded by `doIngest`). Do **not** let it abort the space.
- Stale removal runs **after** the full `pages()` enumeration returns; if enumeration throws, `sync` propagates and removal never runs.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/docmind/confluence/ConfluenceSyncServiceTest.java`:

```java
package com.docmind.confluence;

import java.util.List;

import com.docmind.ingestion.IngestionService;
import com.docmind.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest(properties = {
        "docmind.confluence.base-url=https://c.atlassian.net",
        "docmind.confluence.email=me@c.com",
        "docmind.confluence.api-token=tok",
        "docmind.confluence.space-keys=DOCS"
})
class ConfluenceSyncServiceTest {

    @MockitoBean
    ConfluenceClient client;

    @Autowired
    ConfluenceSyncService syncService;

    @Autowired
    IngestionService ingestionService;

    @Autowired
    SearchService searchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private ConfluencePage page(String id, String title, String body) {
        return new ConfluencePage(id, "DOCS", title, body,
                "https://c.atlassian.net/wiki/spaces/DOCS/pages/" + id + "/" + title);
    }

    @BeforeEach
    void cleanStore() {
        jdbcTemplate.execute("TRUNCATE TABLE vector_store");
        jdbcTemplate.execute("TRUNCATE TABLE document_source");
    }

    @Test
    void syncIngestsPagesWithConfluenceMetadataAndCountsThem() {
        when(client.spaceId("DOCS")).thenReturn("111");
        when(client.pages("111")).thenReturn(List.of(
                page("1", "Kafka", "<p>Kafka is an event streaming platform.</p>"),
                page("2", "Redis", "<p>Redis is an in-memory data store.</p>")));

        ConfluenceSyncService.SyncResult result = syncService.sync("DOCS");

        assertThat(result.ingested()).isEqualTo(2);
        assertThat(result.skipped()).isZero();
        assertThat(result.removed()).isZero();

        var results = searchService.search("event streaming", 3);
        assertThat(results).isNotEmpty();
        Integer type = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_source WHERE doc_type = 'CONFLUENCE'", Integer.class);
        assertThat(type).isEqualTo(2);
    }

    @Test
    void secondSyncSkipsUnchangedPages() {
        when(client.spaceId("DOCS")).thenReturn("111");
        when(client.pages("111")).thenReturn(List.of(page("1", "Kafka", "<p>same</p>")));

        syncService.sync("DOCS");
        ConfluenceSyncService.SyncResult second = syncService.sync("DOCS");

        assertThat(second.skipped()).isEqualTo(1);
        assertThat(second.ingested()).isZero();
    }

    @Test
    void stalePagesAreRemovedWhenNoLongerEnumerated() {
        when(client.spaceId("DOCS")).thenReturn("111");
        when(client.pages("111")).thenReturn(List.of(
                page("1", "Keep", "<p>keep</p>"),
                page("2", "Drop", "<p>drop</p>")));
        syncService.sync("DOCS");

        when(client.pages("111")).thenReturn(List.of(page("1", "Keep", "<p>keep</p>")));
        ConfluenceSyncService.SyncResult second = syncService.sync("DOCS");

        assertThat(second.removed()).isEqualTo(1);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM document_source WHERE doc_type = 'CONFLUENCE'", Integer.class);
        assertThat(remaining).isEqualTo(1);
    }

    @Test
    void perPageFailureIsCountedNotFatal() {
        when(client.spaceId("DOCS")).thenReturn("111");
        // An empty body yields a JsoupDocumentReader that produces zero documents but does not fail;
        // to force a failure we pass content Jsoup can read yet still land a valid page alongside it.
        when(client.pages("111")).thenReturn(List.of(
                page("1", "Good", "<p>good content</p>"),
                new ConfluencePage("2", "DOCS", "Bad", null,
                        "https://c.atlassian.net/wiki/spaces/DOCS/pages/2/Bad")));

        ConfluenceSyncService.SyncResult result = syncService.sync("DOCS");

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.ingested()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw test -Dtest=ConfluenceSyncServiceTest`
Expected: COMPILE FAILURE — `ConfluenceSyncService`, `SyncResult`, and the repository method do not exist.

- [ ] **Step 3: Add the repository stale-lookup method**

In `src/main/java/com/docmind/domain/DocumentSourceRepository.java`, add inside the interface (after `findBySourceUri`):

```java
    java.util.List<DocumentSource> findByDocTypeAndSourceUriStartingWith(String docType, String prefix);
```

- [ ] **Step 4: Create `ConfluenceSyncService`**

Create `src/main/java/com/docmind/confluence/ConfluenceSyncService.java`:

```java
package com.docmind.confluence;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.docmind.config.DocmindProperties;
import com.docmind.domain.DocumentSource;
import com.docmind.domain.DocumentSourceRepository;
import com.docmind.ingestion.IngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "docmind.confluence.base-url")
public class ConfluenceSyncService {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceSyncService.class);

    private final ConfluenceClient client;
    private final IngestionService ingestionService;
    private final DocumentSourceRepository repository;
    private final String baseUrl;
    private final List<String> configuredSpaceKeys;

    public ConfluenceSyncService(ConfluenceClient client,
                                 IngestionService ingestionService,
                                 DocumentSourceRepository repository,
                                 DocmindProperties properties) {
        this.client = client;
        this.ingestionService = ingestionService;
        this.repository = repository;
        this.baseUrl = properties.confluence().baseUrl();
        this.configuredSpaceKeys = properties.confluence().spaceKeys();
    }

    public List<String> configuredSpaceKeys() {
        return configuredSpaceKeys;
    }

    public SyncResult sync(String spaceKey) {
        String spaceId = client.spaceId(spaceKey);
        List<ConfluencePage> pages = client.pages(spaceId);   // full enumeration; throws abort the sync

        int ingested = 0;
        int skipped = 0;
        int failed = 0;
        Set<String> seen = new HashSet<>();
        for (ConfluencePage page : pages) {
            String sourceUri = sourceUri(page.spaceKey(), page.id());
            seen.add(sourceUri);
            try {
                IngestionService.IngestOutcome outcome =
                        ingestionService.ingestConfluencePage(sourceUri, page.title(), page.body());
                if (outcome.skipped()) {
                    skipped++;
                }
                else {
                    ingested++;
                }
            }
            catch (RuntimeException e) {
                log.warn("Failed to ingest Confluence page {}: {}", page.id(), e.getMessage());
                failed++;
            }
        }

        int removed = removeStale(spaceKey, seen);
        return new SyncResult(spaceKey, ingested, skipped, failed, removed);
    }

    public DocumentSource ingestPage(String pageId) {
        ConfluencePage page = client.page(pageId);
        String sourceUri = sourceUri(page.spaceKey(), page.id());
        return ingestionService.ingestConfluencePage(sourceUri, page.title(), page.body()).document();
    }

    private int removeStale(String spaceKey, Set<String> seen) {
        String prefix = baseUrl + "/wiki/spaces/" + spaceKey + "/pages/";
        int removed = 0;
        for (DocumentSource doc : repository.findByDocTypeAndSourceUriStartingWith("CONFLUENCE", prefix)) {
            if (!seen.contains(doc.sourceUri())) {
                ingestionService.removeDocument(doc.id());
                removed++;
            }
        }
        return removed;
    }

    private String sourceUri(String spaceKey, String pageId) {
        return baseUrl + "/wiki/spaces/" + spaceKey + "/pages/" + pageId;
    }

    public record SyncResult(String spaceKey, int ingested, int skipped, int failed, int removed) {
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run (with Docker up): `.\mvnw test -Dtest=ConfluenceSyncServiceTest`
Expected: PASS (4 tests).

> If `perPageFailureIsCountedNotFatal` does not fail-count (i.e. `JsoupDocumentReader` tolerates a `null`/empty body without throwing), adjust the "Bad" page to a body that makes `ingestConfluencePage` throw — the assertion is that one page failing is counted and does not abort. The mechanism under test is the try/catch in `sync`, not Jsoup's tolerance.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/docmind/domain/DocumentSourceRepository.java src/main/java/com/docmind/confluence/ConfluenceSyncService.java src/test/java/com/docmind/confluence/ConfluenceSyncServiceTest.java
git commit -m "feat: ConfluenceSyncService with stale-page removal"
```

---

## Task 5: `ConfluenceTools` (MCP surface)

**Files:**
- Create: `src/main/java/com/docmind/mcp/ConfluenceTools.java`
- Test: `src/test/java/com/docmind/mcp/ConfluenceToolsTest.java`

**Interfaces:**
- Produces: `ConfluenceTools(ConfluenceSyncService syncService)` — `@Component`, `@ConditionalOnProperty(name = "docmind.confluence.base-url")`.
  - `@McpTool sync_confluence(String spaceKey?)` — no arg syncs every configured space (one line each); an arg syncs that one space. Renders `SyncResult` as `Synced space DOCS: 3 ingested, 39 skipped, 1 failed, 2 removed`. Any error becomes `Error: …`.
  - `@McpTool ingest_confluence_page(String pageId)` — ingests one page; renders `Ingested '<title>' (id=…, N chunks, status=…)`; errors become `Error: …`.
- Consumes: `ConfluenceSyncService.sync`, `.ingestPage`, `.configuredSpaceKeys`, `SyncResult`, `DocumentSource`.

**Design notes:** mirror `IngestionToolsTest`'s plain-Mockito style. Keep the tool thin: loop + format + `try/catch` translating exceptions to `"Error: " + e.getMessage()`. For the no-arg path, each configured space is synced in its own `try/catch` so one space's failure produces an error line without aborting the others.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/docmind/mcp/ConfluenceToolsTest.java`:

```java
package com.docmind.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.docmind.confluence.ConfluenceException;
import com.docmind.confluence.ConfluenceSyncService;
import com.docmind.confluence.ConfluenceSyncService.SyncResult;
import com.docmind.domain.DocumentSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfluenceToolsTest {

    @Mock
    ConfluenceSyncService syncService;

    @InjectMocks
    ConfluenceTools tools;

    @Test
    void syncOneSpaceFormatsCounts() {
        when(syncService.sync("DOCS")).thenReturn(new SyncResult("DOCS", 3, 39, 1, 2));

        assertThat(tools.syncConfluence("DOCS"))
                .isEqualTo("Synced space DOCS: 3 ingested, 39 skipped, 1 failed, 2 removed");
    }

    @Test
    void syncNoArgSyncsEveryConfiguredSpace() {
        when(syncService.configuredSpaceKeys()).thenReturn(List.of("DOCS", "ENG"));
        when(syncService.sync("DOCS")).thenReturn(new SyncResult("DOCS", 1, 0, 0, 0));
        when(syncService.sync("ENG")).thenReturn(new SyncResult("ENG", 2, 0, 0, 0));

        String out = tools.syncConfluence(null);

        assertThat(out).contains("Synced space DOCS").contains("Synced space ENG");
    }

    @Test
    void syncErrorReturnsStructuredStringNotStackTrace() {
        when(syncService.sync("NOPE")).thenThrow(new ConfluenceException("Confluence space not found: NOPE"));

        assertThat(tools.syncConfluence("NOPE")).startsWith("Error:").contains("NOPE");
    }

    @Test
    void ingestPageFormatsSuccess() {
        UUID id = UUID.randomUUID();
        when(syncService.ingestPage("456")).thenReturn(new DocumentSource(
                id, "Deploy Guide", "https://c.atlassian.net/wiki/spaces/DOCS/pages/456",
                "CONFLUENCE", "abc", 4, null, "INGESTED", Instant.now()));

        assertThat(tools.ingestConfluencePage("456"))
                .contains(id.toString()).contains("4 chunks").contains("INGESTED");
    }

    @Test
    void ingestPageErrorReturnsStructuredString() {
        when(syncService.ingestPage("999")).thenThrow(new ConfluenceException("Confluence page not found: 999"));

        assertThat(tools.ingestConfluencePage("999")).startsWith("Error:").contains("999");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw test -Dtest=ConfluenceToolsTest`
Expected: COMPILE FAILURE — `ConfluenceTools` does not exist.

- [ ] **Step 3: Create `ConfluenceTools`**

Create `src/main/java/com/docmind/mcp/ConfluenceTools.java`:

```java
package com.docmind.mcp;

import com.docmind.confluence.ConfluenceSyncService;
import com.docmind.confluence.ConfluenceSyncService.SyncResult;
import com.docmind.domain.DocumentSource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.confluence.base-url")
public class ConfluenceTools {

    private final ConfluenceSyncService syncService;

    public ConfluenceTools(ConfluenceSyncService syncService) {
        this.syncService = syncService;
    }

    @McpTool(name = "sync_confluence", description = """
            Sync Confluence Cloud pages into the documentation index. With no space key, \
            syncs every configured space; with a space key, syncs just that space. \
            Unchanged pages are skipped; pages removed from Confluence are removed from the index.""")
    public String syncConfluence(
            @McpToolParam(description = "Optional Confluence space key (e.g. DOCS); omit to sync all configured spaces",
                    required = false) String spaceKey) {
        if (spaceKey != null && !spaceKey.isBlank()) {
            return syncOne(spaceKey.trim());
        }
        StringBuilder out = new StringBuilder();
        for (String key : syncService.configuredSpaceKeys()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(syncOne(key));
        }
        return out.length() == 0 ? "No Confluence spaces configured" : out.toString();
    }

    @McpTool(name = "ingest_confluence_page", description = """
            Fetch a single Confluence Cloud page by its page id and ingest it into the \
            documentation index. Re-ingesting an unchanged page is a no-op.""")
    public String ingestConfluencePage(
            @McpToolParam(description = "Confluence page id (numeric string)") String pageId) {
        try {
            DocumentSource doc = syncService.ingestPage(pageId);
            return "Ingested '%s' (id=%s, %d chunks, status=%s)"
                    .formatted(doc.title(), doc.id(), doc.chunkCount(), doc.status());
        }
        catch (Exception e) {
            return "Error: could not ingest Confluence page " + pageId + " — " + e.getMessage();
        }
    }

    private String syncOne(String spaceKey) {
        try {
            SyncResult r = syncService.sync(spaceKey);
            return "Synced space %s: %d ingested, %d skipped, %d failed, %d removed"
                    .formatted(r.spaceKey(), r.ingested(), r.skipped(), r.failed(), r.removed());
        }
        catch (Exception e) {
            return "Error: could not sync Confluence space " + spaceKey + " — " + e.getMessage();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw test -Dtest=ConfluenceToolsTest`
Expected: PASS (5 tests). No Docker.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/mcp/ConfluenceTools.java src/test/java/com/docmind/mcp/ConfluenceToolsTest.java
git commit -m "feat: ConfluenceTools MCP surface (sync_confluence, ingest_confluence_page)"
```

---

## Task 6: `ConfluenceSyncScheduler` (optional periodic re-sync)

**Files:**
- Create: `src/main/java/com/docmind/confluence/ConfluenceSyncScheduler.java`
- Test: `src/test/java/com/docmind/confluence/ConfluenceSyncSchedulerTest.java`

**Interfaces:**
- Produces: `ConfluenceSyncScheduler(ConfluenceSyncService syncService)` — `@Component`, `@ConditionalOnProperty(name = "docmind.confluence.sync-enabled", havingValue = "true")`, `@Scheduled(fixedDelayString = "${docmind.confluence.sync-interval:PT1H}")` method `syncAll()` that syncs every configured space, catching per-space failures so one bad space never stops the rest.
- Consumes: `ConfluenceSyncService.configuredSpaceKeys()`, `.sync(String)`.

**Design note:** mirrors `RescanScheduler` (src/main/java/com/docmind/ingestion/RescanScheduler.java) but iterates configured spaces and swallows/logs per-space exceptions (a scheduled job has no caller to surface errors to).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/docmind/confluence/ConfluenceSyncSchedulerTest.java`:

```java
package com.docmind.confluence;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfluenceSyncSchedulerTest {

    @Test
    void syncAllDelegatesToServiceForEachConfiguredSpace() {
        ConfluenceSyncService service = mock(ConfluenceSyncService.class);
        when(service.configuredSpaceKeys()).thenReturn(List.of("DOCS", "ENG"));

        new ConfluenceSyncScheduler(service).syncAll();

        verify(service).sync("DOCS");
        verify(service).sync("ENG");
    }

    @Test
    void oneSpaceFailingDoesNotStopTheOthers() {
        ConfluenceSyncService service = mock(ConfluenceSyncService.class);
        when(service.configuredSpaceKeys()).thenReturn(List.of("DOCS", "ENG"));
        when(service.sync("DOCS")).thenThrow(new ConfluenceException("boom"));

        new ConfluenceSyncScheduler(service).syncAll();

        verify(service).sync("ENG");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\mvnw test -Dtest=ConfluenceSyncSchedulerTest`
Expected: COMPILE FAILURE — `ConfluenceSyncScheduler` does not exist.

- [ ] **Step 3: Create `ConfluenceSyncScheduler`**

Create `src/main/java/com/docmind/confluence/ConfluenceSyncScheduler.java`:

```java
package com.docmind.confluence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.confluence.sync-enabled", havingValue = "true")
public class ConfluenceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceSyncScheduler.class);

    private final ConfluenceSyncService syncService;

    public ConfluenceSyncScheduler(ConfluenceSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelayString = "${docmind.confluence.sync-interval:PT1H}")
    public void syncAll() {
        for (String spaceKey : syncService.configuredSpaceKeys()) {
            try {
                ConfluenceSyncService.SyncResult r = syncService.sync(spaceKey);
                log.info("Scheduled sync of {}: {} ingested, {} skipped, {} failed, {} removed",
                        r.spaceKey(), r.ingested(), r.skipped(), r.failed(), r.removed());
            }
            catch (RuntimeException e) {
                log.warn("Scheduled sync of space {} failed: {}", spaceKey, e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\mvnw test -Dtest=ConfluenceSyncSchedulerTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/confluence/ConfluenceSyncScheduler.java src/test/java/com/docmind/confluence/ConfluenceSyncSchedulerTest.java
git commit -m "feat: optional scheduled Confluence re-sync"
```

---

## Task 7: Documentation + full-suite verification

**Files:**
- Modify: `README.md`

**Interfaces:** none (docs + verification only).

- [ ] **Step 1: Document Confluence in the README**

In `README.md`, add a "Confluence Cloud ingestion" section covering:
- The `docmind.confluence` config block (copy the commented YAML from `application.yml`).
- `CONFLUENCE_API_TOKEN` is supplied only as an environment variable; create one at
  **id.atlassian.com → Security → API tokens** (https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/).
- The two tools: `sync_confluence(spaceKey?)` and `ingest_confluence_page(pageId)`, and that scheduled re-sync is enabled with `docmind.confluence.sync-enabled: true`.
- That confluence is disabled unless `docmind.confluence.base-url` is set.

Example block to insert (adapt to the README's existing heading style):

```markdown
## Confluence Cloud ingestion

Set `docmind.confluence.base-url` to enable. Confluence pages become searchable
through the same `search_docs` tool as local files and web pages.

    docmind:
      confluence:
        base-url: https://yourcompany.atlassian.net   # no trailing /wiki
        email: you@company.com
        api-token: ${CONFLUENCE_API_TOKEN}            # env var only — never commit
        space-keys: [DOCS, ENG]
        sync-enabled: false                            # true to re-sync on a timer
        sync-interval: PT1H

Create an API token at id.atlassian.com → Security → API tokens and export it as
`CONFLUENCE_API_TOKEN` before starting the server.

Tools:
- `sync_confluence` — no argument syncs every configured space; pass a space key to sync one.
- `ingest_confluence_page` — ingest a single page by its numeric page id.
```

- [ ] **Step 2: Commit the docs**

```bash
git add README.md
git commit -m "docs: document Confluence Cloud ingestion"
```

- [ ] **Step 3: Run the fast (no-Docker) suite**

Run: `.\mvnw test -Dtest=DocmindPropertiesTest,ConfluenceClientTest,ConfluenceToolsTest,ConfluenceSyncSchedulerTest,IngestionToolsTest`
Expected: PASS.

- [ ] **Step 4: Run the full suite with Docker up**

Run: `docker compose up -d` then `.\mvnw test`
Expected: PASS (all suites, including the `@SpringBootTest` Confluence and ingestion integration tests).

> The default Spring context (no `docmind.confluence.base-url`) must still start with **no** Confluence beans — `DocmindApplicationTests` and `McpEndpointSmokeTest` passing confirms the `@ConditionalOnProperty` guard.

- [ ] **Step 5: Final commit if anything remains**

```bash
git status
# commit any stragglers only if git status is not clean
```

---

## Self-Review

**Spec coverage:**
- v2 REST access (Basic auth, one call per page, cursor pagination) → Task 2 `ConfluenceClient` ✅
- `sync_confluence(spaceKey?)` all-vs-one + `ingest_confluence_page(pageId)` → Task 5 `ConfluenceTools` ✅
- Checksum = SHA-256 of `title + "\n" + body` via `doIngest` checksum-skip → Task 3 ✅
- Cloud-only v2 endpoints → Task 2 (endpoints hardcoded to `/wiki/api/v2`) ✅
- `com.docmind.confluence` package + thin MCP layer → Tasks 2/4/5/6 ✅
- `IngestionService.ingestConfluencePage(sourceUri, title, storageXhtml)` → doIngest with `CONFLUENCE` + `JsoupDocumentReader` → Task 3 ✅
- `@ConditionalOnProperty(name = "docmind.confluence.base-url")` on every bean → Tasks 2/4/5 (and `sync-enabled` on the scheduler, Task 6) ✅
- `source_uri` formula + `doc_type = CONFLUENCE` + checksum → Tasks 3/4 ✅
- Sync behavior: enumerate → ingest → stale removal → `SyncResult(spaceKey, ingested, skipped, failed, removed)` rendered as the exact string → Tasks 4/5 ✅
- Per-page failure counted not fatal → Task 4 (try/catch in `sync`) ✅
- Scheduled re-sync mirroring `RescanScheduler`, disabled by default → Task 6 ✅
- Config nested record + env-only token → Task 1 + Task 7 README ✅
- Error handling: structured `Error:` strings, 401/403 auth message, space-not-found, 404 page, partial sync never deletes → Tasks 2/4/5 ✅
- Tests: `ConfluenceClientTest` (MockRestServiceServer), `ConfluenceSyncServiceTest` (@SpringBootTest + @MockitoBean), `ConfluenceToolsTest` (plain Mockito) → Tasks 2/4/5 ✅
- Out-of-scope items (Data Center, attachments, CQL, version-number sync) → not implemented, `ConfluenceClient` left as the seam ✅

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to Task N" — every code and test block is complete. ✅

**Type consistency:** `IngestOutcome(document, skipped)` (Task 3) is consumed identically in Task 4; `SyncResult(spaceKey, ingested, skipped, failed, removed)` (Task 4) is rendered with matching accessors in Task 5 and logged with matching accessors in Task 6; `ConfluencePage(id, spaceKey, title, body, webUrl)` (Task 2) is constructed in Task 4's test and consumed in Task 4's service; `ConfluenceClient.spaceId/pages/page` signatures match between Task 2 and Task 4; `findByDocTypeAndSourceUriStartingWith` (Task 4) matches its single call site. ✅

**Assumption to verify during execution (Task 2):** the v2 JSON field paths (`results[]`, `_links.next`, `_links.webui`, `body.storage.value`) are asserted against author-controlled mock JSON in `ConfluenceClientTest`, and against a real space in the spec's manual verification step (sync a real space, `search_docs`, follow the `source_uri` link).
