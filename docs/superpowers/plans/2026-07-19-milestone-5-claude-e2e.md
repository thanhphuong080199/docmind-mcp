# Milestone 5: Claude End-to-End (registration, eval, tuning) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Connect docmind to Claude Code and Claude Desktop, evaluate retrieval quality against a written question set, and tune threshold/topK/tool descriptions based on results.

**Architecture:** One code change — a configurable similarity threshold in `SearchService` (scores below it are noise for the client LLM; pgvector cosine similarity from `nomic-embed-text` typically lands ~0.75 for on-topic hits, ~0.45 for off-topic, so a threshold cuts junk without hiding real matches). Everything else is registration (Claude Code CLI, Claude Desktop connector or `mcp-remote` bridge), a persisted eval question set, and description/config tuning driven by observed Claude behavior.

**Tech Stack:** Claude Code CLI (`claude mcp add --transport http`), Claude Desktop, `mcp-remote` bridge (Node), existing Spring stack.

## Global Constraints

- Java 25; prefix every Maven call: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd ...` (PowerShell). Wrapper only.
- Tests need `docker compose up -d` with `nomic-embed-text` and `qwen3:4b` pulled.
- Server must run on the fixed port 8080 — both client registrations hardcode `http://localhost:8080/mcp`.
- Similarity scores are model-specific (`nomic-embed-text`); thresholds tuned here are invalid if the embedding model changes.
- Tool names (`search_docs`, `list_available_docs`, `get_doc_summary`, `ingest_document`, `remove_document`) are frozen — tune descriptions, never names.
- Work on branch `milestone-5-claude-e2e` cut from `main` after the Milestone 4 PR merges.

---

### Task 1: Configurable similarity threshold in SearchService

**Files:**
- Modify: `src/main/java/com/docmind/config/DocmindProperties.java`
- Modify: `src/main/java/com/docmind/search/SearchService.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/docmind/search/SearchThresholdTest.java` (create)

**Interfaces:**
- Consumes: `DocmindProperties` (Milestone 3), `VectorStore`, Spring AI `SearchRequest.Builder.similarityThreshold(double)`.
- Produces: `DocmindProperties(Path docsDir, boolean scanOnStartup, double similarityThreshold)` — note the widened constructor; `SearchService` constructor becomes `SearchService(VectorStore, DocmindProperties)` and applies the threshold to every search. Default `0.0` (no filtering) until Task 3 picks a tuned value.

- [ ] **Step 1: Write the failing test**

```java
package com.docmind.search;

import java.nio.file.Files;
import java.nio.file.Path;

import com.docmind.config.DocmindProperties;
import com.docmind.ingestion.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest
class SearchThresholdTest {

    @Autowired
    VectorStore vectorStore;

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
    void thresholdFiltersLowScoringChunks() throws Exception {
        Path file = tempDir.resolve("kafka.md");
        Files.writeString(file, "# Kafka\n\nKafka is a distributed event streaming platform.\n");
        ingestionService.ingestFile(file);

        SearchService permissive = new SearchService(vectorStore,
                new DocmindProperties(null, false, 0.0));
        SearchService strict = new SearchService(vectorStore,
                new DocmindProperties(null, false, 0.99));

        assertThat(permissive.search("event streaming", 5)).isNotEmpty();
        assertThat(strict.search("event streaming", 5)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test -Dtest=SearchThresholdTest`
Expected: COMPILE FAIL — `DocmindProperties` has no 3-arg constructor, `SearchService` has no 2-arg constructor.

- [ ] **Step 3: Implement**

`DocmindProperties` becomes:

```java
package com.docmind.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docmind")
public record DocmindProperties(Path docsDir, boolean scanOnStartup, double similarityThreshold) {

    public DocmindProperties {
        if (docsDir == null) {
            docsDir = Path.of("docs-inbox");
        }
    }
}
```

In `SearchService`, replace the constructor and the 3-arg `search` method (field `private final double similarityThreshold;`):

```java
    public SearchService(VectorStore vectorStore, com.docmind.config.DocmindProperties properties) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = properties.similarityThreshold();
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
                        doc.getText(),
                        doc.getScore()))
                .toList();
    }
```

In `application.yml`, extend the `docmind:` block:

```yaml
docmind:
  docs-dir: ./docs-inbox
  scan-on-startup: false
  similarity-threshold: 0.0
```

- [ ] **Step 4: Run all tests**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green (threshold 0.0 keeps existing tests' behavior identical).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/docmind/ src/main/resources/application.yml src/test/java/com/docmind/search/SearchThresholdTest.java
git commit -m "feat: configurable similarity threshold for search"
```

---

### Task 2: Register with Claude Code and Claude Desktop

**Files:**
- Modify: `README.md` (new "Connecting Claude" section)

**Interfaces:**
- Consumes: running server at `http://localhost:8080/mcp` with all five tools.
- Produces: docmind registered in both clients; README instructions any future machine can follow.

- [ ] **Step 1: Start the stack and ingest real content**

```powershell
docker compose up -d
# put 2-3 real docs you actually use into .\docs-inbox (e.g. downloaded Spring/ Kafka guides, a PDF datasheet)
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd spring-boot:run "-Ddocmind.scan-on-startup=true"
```

- [ ] **Step 2: Register in Claude Code**

```powershell
claude mcp add --transport http docmind http://localhost:8080/mcp
claude mcp list
```

Expected: `docmind: http://localhost:8080/mcp (HTTP) - ✓ Connected`.

- [ ] **Step 3: Verify inside a Claude Code session**

Start `claude`, run `/mcp` — docmind should be listed with its tools. Ask a question only your ingested docs can answer (e.g. "According to my docs, how does X configure Y?") and confirm Claude calls `search_docs` and cites chunk content.

- [ ] **Step 4: Register in Claude Desktop**

Preferred: Settings → Connectors → Add custom connector → name `docmind`, URL `http://localhost:8080/mcp`.
Fallback (if custom connectors are unavailable on the plan): add to `%APPDATA%\Claude\claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "docmind": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

Restart Claude Desktop; the tools appear under the connectors icon. Ask the same test question.

- [ ] **Step 5: Document in README and commit**

Add a "Connecting Claude" section containing exactly the commands/config from Steps 2 and 4, plus the note that the server must be running (`docker compose up -d` + `mvnw spring-boot:run`) before either client connects.

```bash
git add README.md
git commit -m "docs: connect docmind to Claude Code and Claude Desktop"
```

---

### Task 3: Eval question set and baseline run

**Files:**
- Create: `docs/eval/2026-07-eval.md`

**Interfaces:**
- Consumes: registered Claude Code client (Task 2), ingested real docs.
- Produces: a written, repeatable eval: 10 questions with expected source doc, recorded scores/verdicts. Task 4 tunes against it; re-run it after any embedding/chunking change.

- [ ] **Step 1: Write the eval file (template below, fill questions from your actual ingested docs)**

```markdown
# docmind retrieval eval — 2026-07

**Setup:** docs ingested: <list them>; similarity-threshold: 0.0; topK default 5.
Ask each question in Claude Code ("answer from my docs: ...") and record what
search_docs returned (top score, right doc or not) and whether Claude's answer
used the retrieved chunks.

| # | Question | Expected source doc | Top score | Right doc in top 3? | Claude used it? | Notes |
|---|----------|--------------------|-----------|--------------------|-----------------|-------|
| 1 |          |                    |           |                    |                 |       |
| 2 |          |                    |           |                    |                 |       |
...10 rows, mixing: direct factual lookups, "how do I" questions, questions
spanning two docs, one question your docs do NOT answer (expect low scores /
"not found" behavior), one keyword-only query (tests semantic vs lexical).

## Verdict
- Retrieval hit rate (right doc in top 3): x/10
- Observed score range for correct hits: ~a.aa–b.bb  → informs threshold
- Observed score range for the unanswerable question: ~c.cc
- Failure notes: ...
```

- [ ] **Step 2: Run the eval**

For each question, in a Claude Code session ask the question prefixed with "Using my docmind docs, ...". Record every row. Watch the server logs to see the actual `search_docs` calls (query phrasing Claude chose, topK).

- [ ] **Step 3: Commit the filled-in eval**

```bash
git add docs/eval/2026-07-eval.md
git commit -m "docs: baseline retrieval eval"
```

---

### Task 4: Tune threshold, topK and tool descriptions from eval results

**Files:**
- Modify: `src/main/resources/application.yml` (`docmind.similarity-threshold`)
- Modify: `src/main/java/com/docmind/mcp/SearchTools.java` (description text, possibly `DEFAULT_TOP_K`)
- Modify: `docs/eval/2026-07-eval.md` (post-tuning re-run section)

**Interfaces:**
- Consumes: eval verdict (Task 3): correct-hit score range vs noise score range.
- Produces: tuned defaults; descriptions that make Claude pick the right tool with the right arguments.

- [ ] **Step 1: Set the threshold from data**

Pick a value between the unanswerable-question score ceiling and the correct-hit floor (e.g. hits ≥ 0.62 and noise ≤ 0.50 → threshold 0.55). Update `docmind.similarity-threshold` in `application.yml`.

- [ ] **Step 2: Fix observed tool-selection problems**

Common fixes, applied only if the eval showed the problem:
- Claude never calls the tool unprompted → strengthen `search_docs` description ("Always use this before answering questions about the user's own documentation or ingested manuals").
- Claude passes whole sentences as queries that score poorly → add to the `query` param description: "Short keyword-style queries work best".
- Claude asks for too few/many chunks → adjust `DEFAULT_TOP_K` in `SearchTools`.
- Claude ignores multi-doc situations → mention `list_available_docs` + `docId` filtering in the `search_docs` description.

- [ ] **Step 3: Re-run the eval's failed rows and record**

Append to `docs/eval/2026-07-eval.md`:

```markdown
## Post-tuning re-run (threshold=<x>, topK=<k>)
| # | Before | After |
|---|--------|-------|
```

- [ ] **Step 4: Regression tests and commit**

Run: `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot'; .\mvnw.cmd test`
Expected: all green (integration tests use one doc, so a sane threshold ≤0.6 must not break them — if one fails, the threshold is too aggressive; lower it).

```bash
git add src/main/resources/application.yml src/main/java/com/docmind/mcp/SearchTools.java docs/eval/2026-07-eval.md
git commit -m "feat: tune retrieval threshold and tool descriptions from eval"
```

---

## Final verification

- [ ] Claude Code and Claude Desktop both answer a doc-grounded question via `search_docs`.
- [ ] Eval file committed with baseline + post-tuning numbers.
- [ ] `.\mvnw.cmd test` all green.
- [ ] Finish with superpowers:finishing-a-development-branch.
