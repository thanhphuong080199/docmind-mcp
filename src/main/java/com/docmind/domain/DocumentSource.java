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
