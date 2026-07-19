package com.docmind.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.ListCrudRepository;

public interface DocumentSourceRepository extends ListCrudRepository<DocumentSource, UUID> {

    Optional<DocumentSource> findBySourceUri(String sourceUri);
}
