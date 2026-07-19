# docmind-mcp Milestone 1 — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Runnable Spring Boot app that ingests one Markdown file into pgvector (embeddings via local Ollama) and answers semantic search queries from code — no MCP yet.

**Architecture:** Single Spring Boot 4.1 app, package-by-feature under `com.docmind`. Ingestion pipeline: `MarkdownDocumentReader` → `TokenTextSplitter` → metadata enrichment → `VectorStore.add()`. A Flyway-managed `document_source` table tracks doc metadata; Spring AI auto-creates the `vector_store` table. Infra (pgvector + Ollama) runs via docker-compose.

**Tech Stack:** Java 25, Spring Boot 4.1.x, Spring AI 2.0.0 (BOM), PostgreSQL 17 + pgvector, Ollama (`nomic-embed-text`), Flyway, Spring Data JDBC, Maven.

## Global Constraints

- Java 25; Spring Boot parent `4.1.0` (bump to latest 4.1.x patch if newer exists); Spring AI BOM `2.0.0`
- Embedding model `nomic-embed-text` → pgvector `dimensions: 768`, `index-type: HNSW`, `distance-type: COSINE_DISTANCE`
- Base package `com.docmind`; MCP layer must NOT appear in this milestone
- Chunk metadata keys (later milestones depend on these exact names): `doc_id`, `source_uri`, `chunk_index`
- Chat model disabled (`spring.ai.model.chat: none`) — summaries arrive in Milestone 4
- Integration tests run against the docker-compose services on localhost (Postgres 5432, Ollama 11434); `docker compose up -d` is a documented prerequisite
- Spec: `docs/superpowers/specs/2026-07-19-docmind-mcp-design.md`

---

### Task 1: Maven scaffold

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/docmind/DocmindApplication.java`
- Create: `.gitignore`

**Interfaces:**
- Consumes: nothing
- Produces: compilable Maven project; Spring AI 2.0.0 BOM + starters on classpath for all later tasks

- [ ] **Step 1: Write `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>

    <groupId>com.docmind</groupId>
    <artifactId>docmind-mcp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>docmind-mcp</name>
    <description>MCP server for semantic search over technical documentation</description>

    <properties>
        <java.version>25</java.version>
        <spring-ai.version>2.0.0</spring-ai.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-markdown-document-reader</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

Note for the implementer: artifact names above are the Spring AI 2.0 names. If `mvn` cannot resolve one, check https://docs.spring.io/spring-ai/reference/ for the current starter name rather than guessing — do not downgrade to 1.x artifacts.

- [ ] **Step 2: Write the application class**

`src/main/java/com/docmind/DocmindApplication.java`:

```java
package com.docmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DocmindApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocmindApplication.class, args);
    }
}
```

- [ ] **Step 3: Write `.gitignore`**

```gitignore
target/
.idea/
*.iml
.vscode/
.DS_Store
```

- [ ] **Step 4: Verify it compiles**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (downloads dependencies on first run; confirms Spring AI 2.0.0 artifacts resolve)

- [ ] **Step 5: Commit**

```bash
git add pom.xml .gitignore src/
git commit -m "feat: scaffold Spring Boot 4.1 + Spring AI 2.0 Maven project"
```

---

### Task 2: docker-compose infrastructure

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: nothing
- Produces: Postgres+pgvector on `localhost:5432` (db/user/password all `docmind`), Ollama on `localhost:11434` with `nomic-embed-text` pulled

- [ ] **Step 1: Write `docker-compose.yml`**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg17
    container_name: docmind-postgres
    environment:
      POSTGRES_DB: docmind
      POSTGRES_USER: docmind
      POSTGRES_PASSWORD: docmind
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U docmind -d docmind"]
      interval: 5s
      timeout: 3s
      retries: 10

  ollama:
    image: ollama/ollama
    container_name: docmind-ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama_models:/root/.ollama

volumes:
  pgdata:
  ollama_models:
```

- [ ] **Step 2: Start services**

Run: `docker compose up -d`
Expected: both containers report Started; `docker compose ps` shows postgres healthy after ~10s

- [ ] **Step 3: Pull the embedding model**

Run: `docker exec docmind-ollama ollama pull nomic-embed-text`
Expected: download completes (~274 MB), ends with `success`

- [ ] **Step 4: Verify both services respond**

Run: `docker exec docmind-postgres pg_isready -U docmind -d docmind`
Expected: `... accepting connections`

Run: `curl -s http://localhost:11434/api/embed -d "{\"model\":\"nomic-embed-text\",\"input\":\"hello\"}"`
Expected: JSON response containing `"embeddings":[[...` (768 floats)

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add docker-compose with pgvector and Ollama"
```

---

### Task 3: Configuration, Flyway migration, domain

**Files:**
- Create: `src/main/resources/application.yml`
- Create: `src/main/resources/db/migration/V1__create_document_source.sql`
- Create: `src/main/java/com/docmind/domain/DocumentSource.java`
- Create: `src/main/java/com/docmind/domain/DocumentSourceRepository.java`
- Test: `src/test/java/com/docmind/DocmindApplicationTests.java`

**Interfaces:**
- Consumes: running docker-compose services (Task 2)
- Produces: `DocumentSource(UUID id, String title, String sourceUri, String docType, String checksum, Integer chunkCount, String summary, String status, Instant ingestedAt)` record mapped to table `document_source`; `DocumentSourceRepository extends ListCrudRepository<DocumentSource, UUID>` with `Optional<DocumentSource> findBySourceUri(String sourceUri)`; auto-created `vector_store` table (768 dims, HNSW)

- [ ] **Step 1: Write the failing context test**

`src/test/java/com/docmind/DocmindApplicationTests.java`:

```java
package com.docmind;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class DocmindApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=DocmindApplicationTests`
Expected: FAIL — context cannot start (no datasource configured yet)

- [ ] **Step 3: Write `application.yml`**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/docmind
    username: docmind
    password: docmind
  ai:
    model:
      chat: none
    ollama:
      base-url: http://localhost:11434
      init:
        pull-model-strategy: when_missing
      embedding:
        model: nomic-embed-text
    vectorstore:
      pgvector:
        initialize-schema: true
        dimensions: 768
        index-type: HNSW
        distance-type: COSINE_DISTANCE
```

(`pull-model-strategy: when_missing` makes the app pull `nomic-embed-text` automatically if Task 2 Step 3 was skipped; with chat disabled it only manages the embedding model.)

- [ ] **Step 4: Write the Flyway migration**

`src/main/resources/db/migration/V1__create_document_source.sql`:

```sql
CREATE TABLE document_source (
    id          UUID PRIMARY KEY,
    title       TEXT        NOT NULL,
    source_uri  TEXT        NOT NULL UNIQUE,
    doc_type    TEXT        NOT NULL,
    checksum    TEXT        NOT NULL,
    chunk_count INT         NOT NULL DEFAULT 0,
    summary     TEXT,
    status      TEXT        NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 5: Write the domain record and repository**

`src/main/java/com/docmind/domain/DocumentSource.java`:

```java
package com.docmind.domain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("document_source")
public record DocumentSource(
        @Id UUID id,
        String title,
        String sourceUri,
        String docType,
        String checksum,
        Integer chunkCount,
        String summary,
        String status,
        Instant ingestedAt) {
}
```

`src/main/java/com/docmind/domain/DocumentSourceRepository.java`:

```java
package com.docmind.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface DocumentSourceRepository extends ListCrudRepository<DocumentSource, UUID> {

    Optional<DocumentSource> findBySourceUri(String sourceUri);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=DocmindApplicationTests`
Expected: PASS — context starts, Flyway applies V1, Spring AI creates `vector_store`

- [ ] **Step 7: Verify tables exist in Postgres**

Run: `docker exec docmind-postgres psql -U docmind -d docmind -c "\dt"`
Expected: table list includes `document_source`, `flyway_schema_history`, and `vector_store`

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/ src/main/java/com/docmind/domain/ src/test/
git commit -m "feat: add datasource/vectorstore config, Flyway migration, DocumentSource domain"
```

---

### Task 4: Ingestion + search services (TDD)

**Files:**
- Create: `src/test/resources/samples/spring-boot-overview.md`
- Create: `src/main/java/com/docmind/ingestion/IngestionService.java`
- Create: `src/main/java/com/docmind/search/SearchService.java`
- Test: `src/test/java/com/docmind/IngestAndSearchIntegrationTest.java`

**Interfaces:**
- Consumes: `DocumentSource`, `DocumentSourceRepository` (Task 3); running docker-compose services
- Produces:
  - `IngestionService.ingestMarkdown(Resource resource, String title)` → `DocumentSource` (re-ingest of same `sourceUri` replaces old chunks + row)
  - `SearchService.search(String query, int topK)` → `List<SearchService.SearchResult>`
  - `SearchService.SearchResult(String docId, String sourceUri, String content, Double score)` record
  - Milestone 2 will call exactly these two service methods from the MCP layer

- [ ] **Step 1: Write the sample document**

`src/test/resources/samples/spring-boot-overview.md`:

```markdown
# Spring Boot Overview

Spring Boot makes it easy to create stand-alone, production-grade Spring applications
that you can just run. It takes an opinionated view of the Spring platform so you can
get started with minimum configuration.

## Auto-configuration

Auto-configuration attempts to automatically configure your Spring application based on
the jar dependencies you have added. For example, if HSQLDB is on your classpath and you
have not manually configured any database connection beans, Spring Boot auto-configures
an in-memory database. Auto-configuration classes are applied after user-defined beans
and back off when you define your own configuration.

## Starters

Starters are a set of convenient dependency descriptors you can include in your
application. You get a one-stop shop for all the Spring and related technology you need
without having to hunt through sample code and copy-paste loads of dependency
descriptors. For example, spring-boot-starter-data-jpa brings in everything needed for
Spring Data JPA.

## Actuator

The Actuator module provides production-ready features such as health checks, metrics,
and externalized HTTP endpoints for monitoring. Endpoints like /actuator/health and
/actuator/metrics let operators observe a running application without custom code.
```

- [ ] **Step 2: Write the failing integration test**

`src/test/java/com/docmind/IngestAndSearchIntegrationTest.java`:

```java
package com.docmind;

import java.util.List;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionService;
import com.docmind.search.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class IngestAndSearchIntegrationTest {

    @Autowired
    IngestionService ingestionService;

    @Autowired
    SearchService searchService;

    @Test
    void ingestThenSearchReturnsRelevantChunk() {
        DocumentSource doc = ingestionService.ingestMarkdown(
                new ClassPathResource("samples/spring-boot-overview.md"),
                "Spring Boot Overview");

        assertThat(doc.chunkCount()).isGreaterThan(0);
        assertThat(doc.status()).isEqualTo("INGESTED");

        List<SearchService.SearchResult> results =
                searchService.search("How does auto-configuration work?", 3);

        assertThat(results).isNotEmpty();
        assertThat(results.getFirst().docId()).isEqualTo(doc.id().toString());
        assertThat(results.getFirst().content().toLowerCase()).contains("auto-configur");
    }

    @Test
    void reingestingSameSourceReplacesInsteadOfDuplicating() {
        var resource = new ClassPathResource("samples/spring-boot-overview.md");
        ingestionService.ingestMarkdown(resource, "Spring Boot Overview");
        DocumentSource second = ingestionService.ingestMarkdown(resource, "Spring Boot Overview");

        List<SearchService.SearchResult> results = searchService.search("starters", 10);
        long distinctDocIds = results.stream().map(SearchService.SearchResult::docId).distinct().count();

        assertThat(distinctDocIds).isEqualTo(1);
        assertThat(results.getFirst().docId()).isEqualTo(second.id().toString());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=IngestAndSearchIntegrationTest`
Expected: COMPILE FAILURE — `IngestionService` and `SearchService` do not exist yet

- [ ] **Step 4: Implement `IngestionService`**

`src/main/java/com/docmind/ingestion/IngestionService.java`:

```java
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
```

(`JdbcAggregateTemplate.insert` is used instead of `repository.save` because the record carries a pre-assigned `@Id`, which Spring Data JDBC would otherwise treat as an update.)

- [ ] **Step 5: Implement `SearchService`**

`src/main/java/com/docmind/search/SearchService.java`:

```java
package com.docmind.search;

import java.util.List;

import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private final VectorStore vectorStore;

    public SearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<SearchResult> search(String query, int topK) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .build())
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

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=IngestAndSearchIntegrationTest`
Expected: PASS (2 tests). First run embeds ~5 chunks + 2 queries via Ollama — allow a few seconds.

- [ ] **Step 7: Run the full suite**

Run: `mvn test`
Expected: PASS — all tests green

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/docmind/ingestion/ src/main/java/com/docmind/search/ src/test/
git commit -m "feat: add markdown ingestion pipeline and semantic search service"
```

---

### Task 5: Demo runner + README

**Files:**
- Create: `src/main/java/com/docmind/DemoIngestRunner.java`
- Create: `src/main/resources/samples/spring-boot-overview.md` (copy of the test sample)
- Modify: `README.md`

**Interfaces:**
- Consumes: `IngestionService.ingestMarkdown(Resource, String)`, `SearchService.search(String, int)` (Task 4)
- Produces: `demo` Spring profile that ingests the sample and prints search results — the milestone's human-visible proof

- [ ] **Step 1: Copy the sample into main resources**

Copy `src/test/resources/samples/spring-boot-overview.md` to `src/main/resources/samples/spring-boot-overview.md` (identical content; the runner must work in the packaged app without test resources).

- [ ] **Step 2: Write the demo runner**

`src/main/java/com/docmind/DemoIngestRunner.java`:

```java
package com.docmind;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionService;
import com.docmind.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@Profile("demo")
class DemoIngestRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoIngestRunner.class);

    private final IngestionService ingestionService;
    private final SearchService searchService;

    DemoIngestRunner(IngestionService ingestionService, SearchService searchService) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
    }

    @Override
    public void run(String... args) {
        DocumentSource doc = ingestionService.ingestMarkdown(
                new ClassPathResource("samples/spring-boot-overview.md"),
                "Spring Boot Overview");
        log.info("Ingested '{}' as {} ({} chunks)", doc.title(), doc.id(), doc.chunkCount());

        String query = "How does auto-configuration work?";
        log.info("Query: {}", query);
        searchService.search(query, 3).forEach(result ->
                log.info("  [score={}] {}", result.score(),
                        result.content().substring(0, Math.min(120, result.content().length()))));
    }
}
```

- [ ] **Step 3: Run the demo**

Run: `mvn spring-boot:run -Dspring-boot.run.profiles=demo`
Expected: log lines showing `Ingested 'Spring Boot Overview' ...` followed by 3 scored chunks, the top one from the Auto-configuration section. Stop the app with Ctrl+C afterwards.

- [ ] **Step 4: Update README**

Replace `README.md` content with:

````markdown
# docmind-mcp

MCP server for semantic search over technical documentation (Spring Boot 4.1 + Spring AI 2.0
+ pgvector + Ollama). Work in progress — currently at Milestone 1 (ingestion + search, no MCP yet).

## Prerequisites

- Java 25, Maven
- Docker (for PostgreSQL/pgvector and Ollama)

## Quickstart

```bash
docker compose up -d
docker exec docmind-ollama ollama pull nomic-embed-text   # first time only
mvn spring-boot:run -Dspring-boot.run.profiles=demo       # ingest sample doc + run a search
mvn test                                                  # integration tests (needs compose up)
```

## Design

See `docs/superpowers/specs/2026-07-19-docmind-mcp-design.md`.
````

- [ ] **Step 5: Verify the full suite still passes**

Run: `mvn test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/docmind/DemoIngestRunner.java src/main/resources/samples/ README.md
git commit -m "feat: add demo profile runner and quickstart README"
```
