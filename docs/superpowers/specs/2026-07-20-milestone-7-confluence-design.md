# Milestone 7 — Confluence Cloud Ingestion — Design Spec

**Date:** 2026-07-20
**Status:** Approved

## Purpose

Make company documentation on Confluence Cloud searchable through docmind alongside local
files and web pages: pull pages via the Confluence REST API, index them in pgvector, and
keep them fresh with cheap re-syncs. Search stays unified — `search_docs` finds Confluence
content semantically, no separate tool surface for querying it.

## Decisions

| Question | Decision | Rationale |
|---|---|---|
| Access path | Confluence Cloud **v2 REST API** directly (Basic auth: email + API token) | Same credential as Atlassian's MCP server would need, but structured JSON instead of LLM-oriented text blobs; one `RestClient` call per page. Atlassian MCP-as-client and LangChain4j loader considered and rejected (indirection / second framework). |
| Sync granularity | Configured space keys auto-sync **all** their pages; separate tool for ad-hoc single pages | Space-level matches "my team's docs" scope without whole-site noise; single-page tool covers everything else. |
| Change detection | SHA-256 of **title + body** through the existing `doIngest` checksum-skip | No new mechanism; unchanged pages are free on every re-sync. Title included so renames re-ingest. `version.number` not needed. |
| Deployment target | Cloud only (`*.atlassian.net`) | User's company runs Cloud. Data Center (v1 API, Bearer PAT) out of scope. |
| Sequencing | New milestone **after** Milestone 6 executes as written, branch `milestone-7-confluence` | M6 supplies the Jsoup reader dependency and the scheduler pattern this milestone reuses. |

## Architecture

New package `com.docmind.confluence`; MCP layer stays thin per the project rule.

```
com.docmind.confluence
├── ConfluenceClient         # v2 REST API over Spring RestClient, Basic auth.
│                            #   spaceId(key), pages(spaceId) w/ cursor pagination,
│                            #   page(id). Returns ConfluencePage DTOs. No ingestion knowledge.
├── ConfluencePage           # record: id, spaceKey, title, body (storage XHTML), webUrl
├── ConfluenceSyncService    # per configured space: enumerate → ingest each →
│                            #   remove stale docs → SyncResult counts. No HTTP knowledge.
└── ConfluenceSyncScheduler  # optional periodic re-sync (RescanScheduler pattern)

com.docmind.mcp
└── ConfluenceTools          # sync_confluence(spaceKey?), ingest_confluence_page(pageId)

com.docmind.ingestion
└── IngestionService.ingestConfluencePage(sourceUri, title, storageXhtml)
                             # one new public method → doIngest(doc_type="CONFLUENCE",
                             #   JsoupDocumentReader on the XHTML body)
```

All Confluence beans are `@ConditionalOnProperty(name = "docmind.confluence.base-url")`:
an unconfigured docmind exposes no Confluence tools and nothing errors.

### REST endpoints used

| Operation | Endpoint |
|---|---|
| Resolve space key → id | `GET {base}/wiki/api/v2/spaces?keys={KEY}` |
| List pages of a space | `GET {base}/wiki/api/v2/spaces/{id}/pages?body-format=storage&limit=50` + cursor pagination via `_links.next` |
| Fetch one page | `GET {base}/wiki/api/v2/pages/{id}?body-format=storage` |

Confluence storage format is XHTML, so `JsoupDocumentReader` (added by Milestone 6 Task 1)
parses page bodies as-is. If M6 Task 1 was skipped, this milestone adds the
`spring-ai-jsoup-document-reader` dependency itself.

## Identity and change detection

- `source_uri` = `{base-url}/wiki/spaces/{KEY}/pages/{pageId}` — stable across renames
  (no title slug), clickable in a browser, and the dedupe key `doIngest` already uses.
- `doc_type` = `CONFLUENCE` (joins `MARKDOWN | PDF | WEB`; no DB constraint exists on the column).
- Checksum = SHA-256 over UTF-8 bytes of `title + "\n" + body`.

## Sync behavior

`sync_confluence(spaceKey?)` — no argument syncs every configured space; with an argument
syncs that one space. Any space the token can read is allowed — the configured list is
only the default set for no-argument syncs and the scheduler.

Per space:

1. Enumerate all pages (paginated).
2. Ingest each via `ingestConfluencePage` — checksum skip / replace handled by `doIngest`.
3. **Stale removal:** any `CONFLUENCE` document whose `source_uri` starts with
   `{base-url}/wiki/spaces/{KEY}/pages/` but was not seen in this enumeration is removed
   via the existing `removeDocument` — deleted/moved pages stop polluting search.
4. Return `SyncResult(spaceKey, ingested, skipped, failed, removed)`; tool renders it as
   `Synced space DOCS: 3 ingested, 39 skipped, 1 failed, 2 removed`.

One page failing does **not** abort the sync; it is counted, and `doIngest` already records
a `FAILED` `document_source` row for diagnostics.

`ingest_confluence_page(pageId)` fetches one page and ingests it the same way (no stale
removal — that is a space-sync concept).

### Scheduled re-sync

`ConfluenceSyncScheduler` mirrors Milestone 6's `RescanScheduler` exactly:
`@ConditionalOnProperty(name = "docmind.confluence.sync-enabled", havingValue = "true")`,
`@Scheduled(fixedDelayString = "${docmind.confluence.sync-interval:PT1H}")`, delegates to
`ConfluenceSyncService` for all configured spaces. Disabled by default.

## Configuration

Nested record on `DocmindProperties`:

```yaml
docmind:
  confluence:
    base-url: https://yourcompany.atlassian.net   # no trailing /wiki
    email: you@company.com
    api-token: ${CONFLUENCE_API_TOKEN}            # env var only — never committed
    space-keys: [DOCS, ENG]
    sync-enabled: false
    sync-interval: PT1H
```

The API token is supplied exclusively via environment variable; the README documents
creating one at id.atlassian.com → Security → API tokens.

## Error handling

- Tool errors are structured strings (`Error: …`), never stack traces (project rule).
- 401/403 from Confluence → clear message naming auth ("check CONFLUENCE_API_TOKEN /
  email"); space key that does not resolve → "space not found" message naming the key;
  404 page → message with the page id.
- Network failure mid-sync aborts that space's sync with an error summary; already-ingested
  pages from earlier in the run remain (idempotent — next sync resumes via checksum skip).
  Stale removal runs only after a **complete** enumeration, so a partial sync never
  deletes documents.

## Testing

| Test | Style | Verifies |
|---|---|---|
| `ConfluenceClientTest` | `MockRestServiceServer` bound to the `RestClient` (no network, no new dependency) | Basic-auth header, cursor pagination following, DTO mapping, 401 surfaced as clear error |
| `ConfluenceSyncServiceTest` | `@SpringBootTest`, `@MockitoBean ConfluenceClient` feeding fake pages into the real ingestion stack (needs `docker compose up -d`) | Chunks land in pgvector with correct metadata, checksum skip on second sync, stale-page removal, per-page failure counted not fatal |
| `ConfluenceToolsTest` | Plain Mockito (mirrors `IngestionToolsTest`) | Success formatting and structured error strings |

Manual verification: sync a real space, `search_docs` a question only a Confluence page
answers, follow the `source_uri` link back to the page in a browser.

## Out of scope (YAGNI)

- Data Center / Server support (v1 API, Bearer PAT) — `ConfluenceClient` is the seam if
  ever needed.
- Attachments, comments, blog posts — pages only.
- Confluence-side search / CQL passthrough — retrieval is docmind's pgvector search.
- Incremental sync via `version.number` or webhooks — checksum skip is cheap enough.

## Key references

- Confluence Cloud v2 REST API: https://developer.atlassian.com/cloud/confluence/rest/v2/intro/
- API tokens: https://support.atlassian.com/atlassian-account/docs/manage-api-tokens-for-your-atlassian-account/
- Storage format: https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html
- Design spec (project): `2026-07-19-docmind-mcp-design.md`
