# docmind-mcp

An MCP server that turns your private docs into a searchable, always-on knowledge base for
any AI assistant. Built with Spring Boot 4.1 + Spring AI 2.0 + pgvector + Ollama.

Work in progress — currently at Milestone 7 (Confluence Cloud ingestion), building on
Milestone 6 (web ingestion, chunk context, periodic re-scan).

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

Available tools: `search_docs(query, topK?, docId?)`, `get_chunk_context(docId, chunkIndex, window?)`,
`ingest_document(path)`, `ingest_url(url, title?)`, `remove_document(docId)`,
`list_available_docs()`, `get_doc_summary(docId)`.
With Confluence configured (see below): `sync_confluence(spaceKey?)`, `ingest_confluence_page(pageId)`.
Resource: `docmind://docs` (JSON document catalog).

Drop `.md`/`.pdf` files into `./docs-inbox` and set `docmind.scan-on-startup: true`
(or call the `ingest_document` tool) to index them. Unchanged files are skipped by
SHA-256 checksum; changed files are re-ingested. Set `docmind.rescan-enabled: true`
to re-scan the folder periodically (`docmind.rescan-interval`, default 10 minutes).

## Confluence Cloud ingestion

Set `docmind.confluence.base-url` to enable. Confluence pages become searchable
through the same `search_docs` tool as local files and web pages; leave `base-url`
unset and no Confluence beans are created.

```yaml
docmind:
  confluence:
    base-url: https://yourcompany.atlassian.net   # no trailing /wiki
    email: you@company.com
    api-token: ${CONFLUENCE_API_TOKEN}            # env var only — never commit
    space-keys: [DOCS, ENG]
    sync-enabled: false                            # true to re-sync on a timer
    sync-interval: PT1H
```

Create an API token at **id.atlassian.com → Security → API tokens**
([docs](https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/))
and export it as `CONFLUENCE_API_TOKEN` before starting the server. The token is
read only from the environment and must never be committed. Cloud only
(`*.atlassian.net`, v2 REST API).

Tools:
- `sync_confluence` — no argument syncs every configured space; pass a space key to sync one.
  Unchanged pages are skipped by checksum; pages removed from Confluence are removed from the index.
- `ingest_confluence_page` — ingest a single page by its numeric page id.

Set `docmind.confluence.sync-enabled: true` to re-sync every configured space on a
timer (`docmind.confluence.sync-interval`, default 1 hour).

## Connecting Claude

The server must be running first (`docker compose up -d`, then
`mvnw spring-boot:run`). Both registrations point at `http://localhost:8080/mcp`.

**Claude Code:**

```powershell
claude mcp add --transport http docmind http://localhost:8080/mcp
claude mcp list   # expect: docmind ... - ✓ Connected
```

Inside a session, `/mcp` lists docmind's tools. Ask a question only your ingested
docs can answer and Claude will call `search_docs`.

**Claude Desktop:** Settings → Connectors → Add custom connector → name
`docmind`, URL `http://localhost:8080/mcp`. If custom connectors are unavailable
on your plan, add this to `%APPDATA%\Claude\claude_desktop_config.json` instead
and restart Claude Desktop:

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

## Design

See `docs/superpowers/specs/2026-07-19-docmind-mcp-design.md`.
