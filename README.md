# docmind-mcp

An MCP server that turns your private docs into a searchable, always-on knowledge base for
any AI assistant. Built with Spring Boot 4.1 + Spring AI 2.0 + pgvector + Ollama.

Work in progress — currently at Milestone 4 (catalog + LLM summaries).

## Prerequisites

- Java 25 (Maven Wrapper included — no Maven install needed)
- Docker (for PostgreSQL/pgvector and Ollama)

## Quickstart

```bash
docker compose up -d
docker exec docmind-ollama ollama pull nomic-embed-text   # first time only
docker exec docmind-ollama ollama pull qwen3:4b           # first time only (~2.6 GB, used for summaries)
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo    # ingest sample doc + run a search
./mvnw test                                               # integration tests (needs compose up)
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

### Troubleshooting: Docker Hub pulls fail with CDN EOF errors

If `docker compose up` fails pulling images with `production.cloudfront.docker.com ... EOF`,
pull via Google's mirror and retag:

```bash
docker pull mirror.gcr.io/pgvector/pgvector:pg17 && docker tag mirror.gcr.io/pgvector/pgvector:pg17 pgvector/pgvector:pg17
docker pull mirror.gcr.io/ollama/ollama:latest && docker tag mirror.gcr.io/ollama/ollama:latest ollama/ollama:latest
```

## Using the MCP server

Start the app, then connect any MCP client to `http://localhost:8080/mcp`
(Streamable-HTTP). Quick check with MCP Inspector:

```bash
npx @modelcontextprotocol/inspector
# Transport: Streamable HTTP, URL: http://localhost:8080/mcp
```

Available tools: `search_docs(query, topK?, docId?)`, `ingest_document(path)`,
`remove_document(docId)`, `list_available_docs()`, `get_doc_summary(docId)`.
Resource: `docmind://docs` (JSON document catalog).

Drop `.md`/`.pdf` files into `./docs-inbox` and set `docmind.scan-on-startup: true`
(or call the `ingest_document` tool) to index them. Unchanged files are skipped by
SHA-256 checksum; changed files are re-ingested.

## Design

See `docs/superpowers/specs/2026-07-19-docmind-mcp-design.md`.
