# docmind-mcp — Design Spec

**Date:** 2026-07-19
**Status:** Approved

## Purpose

A personal, long-term-use MCP server that ingests technical documentation (Markdown, PDF, later web pages) into a pgvector store and exposes retrieval tools to AI clients (Claude Desktop, Claude Code, Cursor), grounding their answers in real documents (RAG). Secondary goal: learn Spring AI 2.0 + MCP.

## Stack (decided)

| Concern | Choice | Rationale |
|---|---|---|
| Language/runtime | Java 25 (LTS) | Boot 4.1 supports 17–26; 25 recommended for virtual threads/GC |
| Framework | Spring Boot 4.1.x + Spring AI 2.0.0 | Spring AI 2.0 GA (2026-06-12) requires Boot 4.1 / Framework 7 |
| MCP server | `spring-ai-starter-mcp-server-webmvc`, `spring.ai.mcp.server.protocol=STREAMABLE`, stateful, endpoint `/mcp` | Streamable-HTTP is the current MCP standard; SSE deprecated in Spring AI 2.0. WebMVC over WebFlux — no need for reactive |
| Embeddings | Ollama `nomic-embed-text`, 768 dims | Free, local, no API key; outperforms OpenAI text-embedding-3-small on MTEB |
| Summary LLM | Ollama chat model (e.g. `qwen3:4b`) via ChatClient | Only server-side generation use; key-free; swappable bean |
| Vector store | PostgreSQL + pgvector, HNSW index, cosine distance, `dimensions=768` | HNSW: better recall/speed than IVFFlat, no training step |
| Infra | docker-compose: `pgvector/pgvector:pg17` + `ollama/ollama` (volume-persisted models) | One-command startup. Ollama runs CPU in Docker; native app is a zero-code fallback if GPU wanted |
| Migrations | Flyway for `document_source`; Spring AI `initialize-schema=true` for `vector_store` | Each tool owns its table |

## Architecture

Single Spring Boot app, package-by-feature:

```
com.docmind
├── DocmindApplication.java
├── config/          # PgVector, Ollama, MCP server config
├── domain/          # DocumentSource entity + Spring Data repository
├── ingestion/       # reader dispatch (MD/PDF), TokenTextSplitter pipeline,
│                    #   IngestionService (checksum dedup, re-ingest)
├── search/          # SearchService wrapping VectorStore.similaritySearch
├── summary/         # SummaryService (ChatClient, lazy + cached)
└── mcp/             # @McpTool / @McpResource endpoints (thin over services)
```

MCP layer stays thin; all logic lives in services (testable without MCP).

## Data schema

Spring AI owns `vector_store` (id, content, metadata jsonb, embedding vector(768)).
Flyway-managed metadata table:

```sql
document_source (
  id           UUID PK,
  title        TEXT,
  source_uri   TEXT UNIQUE,        -- file path or URL
  doc_type     TEXT,               -- MARKDOWN | PDF | WEB
  checksum     TEXT,               -- SHA-256; skip unchanged re-ingest
  chunk_count  INT,
  summary      TEXT NULL,          -- cached lazily on first get_doc_summary
  status       TEXT,               -- INGESTED | FAILED | STALE
  ingested_at  TIMESTAMPTZ
)
```

Every chunk's `vector_store.metadata` carries `{doc_id, source_uri, chunk_index}` — enables per-doc filtering (`FilterExpression`) and deletion.

## MCP surface

| Tool | Behavior |
|---|---|
| `search_docs(query, topK?, docId?)` | Semantic search; optional per-doc filter; returns chunks + source + score |
| `list_available_docs()` | From `document_source`; includes chunk counts, status |
| `get_doc_summary(docId)` | Lazy: ChatClient on first call, cached in `summary` column |
| `ingest_document(path)` | Ingest a file on request |
| `remove_document(docId)` | Delete chunks + metadata row |
| `get_chunk_context(chunkId)` *(stretch)* | Neighboring chunks around a hit (small-to-big retrieval) |

Plus `@McpResource` `docmind://docs` exposing the doc list (idiomatic MCP; exercises the resource annotation).

RAG split: server does retrieval; the client LLM (Claude) does generation. Only `get_doc_summary` calls an LLM server-side.

## Ingestion pipeline

Reader (by file type: `MarkdownDocumentReader` with `includeCodeBlock(true)` / `PagePdfDocumentReader`) → `TokenTextSplitter` (default ~800-token chunks) → attach metadata → `VectorStore.add()`. Checksum (SHA-256) dedup: unchanged files are skipped; changed files get chunks deleted + re-ingested. Startup scan of configured `docs/` folder.

## Error handling

- Ingestion failure → `document_source.status = FAILED`, error logged; other docs unaffected.
- Tool calls return structured error messages (MCP error result), never stack traces.
- Ollama unreachable → search/ingest fail fast with a clear message naming the dependency.

## Testing

- Integration tests with Testcontainers (pgvector) where practical; embedding calls against local Ollama.
- Milestone 2+: MCP Inspector (`npx @modelcontextprotocol/inspector`) against the Streamable-HTTP endpoint.
- Milestone 5: end-to-end via Claude Code (`claude mcp add --transport http docmind http://localhost:8080/mcp`) and Claude Desktop (custom connector or `mcp-remote` bridge).

## Milestones

1. **Foundation** — scaffold, docker-compose (pgvector + Ollama), Flyway migration, ingest one hardcoded markdown via `CommandLineRunner`, similarity search verified by integration test. No MCP.
2. **First MCP tool** — MCP server starter (Streamable-HTTP), `search_docs` via `@McpTool`, verified with MCP Inspector.
3. **Real ingestion** — IngestionService with checksum dedup + re-ingest, startup folder scan, `ingest_document` + `remove_document`, PDF support.
4. **Catalog & summaries** — `list_available_docs`, `get_doc_summary` (ChatClient + cache), `@McpResource`.
5. **Claude end-to-end** — register with Claude Code + Claude Desktop; eval question set; tune topK/threshold/tool descriptions.
6. **Stretch** — web ingestion (Jsoup/Tika), `get_chunk_context`, folder watch.

## Key references

- Spring AI 2.0 GA announcement: https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- MCP server starter (Streamable-HTTP): https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html
- MCP overview / migration notes: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html
- PgVectorStore: https://docs.spring.io/spring-ai/docs/2.0.0/api/org/springframework/ai/vectorstore/pgvector/autoconfigure/PgVectorStoreProperties.html
- Boot system requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Claude Code MCP: https://code.claude.com/docs/en/mcp
- nomic-embed-text: https://www.nomic.ai/news/nomic-embed-text-v1
