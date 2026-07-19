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
