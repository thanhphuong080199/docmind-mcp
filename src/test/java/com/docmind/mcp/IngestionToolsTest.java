package com.docmind.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.docmind.domain.DocumentSource;
import com.docmind.ingestion.IngestionException;
import com.docmind.ingestion.IngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionToolsTest {

    @Mock
    IngestionService ingestionService;

    @InjectMocks
    IngestionTools tools;

    @TempDir
    Path tempDir;

    @Test
    void ingestReportsSuccess() throws Exception {
        Path file = tempDir.resolve("doc.md");
        Files.writeString(file, "# Doc");
        UUID id = UUID.randomUUID();
        when(ingestionService.ingestFile(any(Path.class))).thenReturn(new DocumentSource(
                id, "doc", file.toUri().toString(), "MARKDOWN", "abc", 3, null, "INGESTED", Instant.now()));

        assertThat(tools.ingestDocument(file.toString()))
                .contains(id.toString()).contains("3 chunks").contains("INGESTED");
    }

    @Test
    void ingestMissingFileReturnsErrorMessage() {
        assertThat(tools.ingestDocument(tempDir.resolve("nope.md").toString()))
                .startsWith("Error:");
    }

    @Test
    void ingestFailureReturnsErrorMessageNotStackTrace() throws Exception {
        Path file = tempDir.resolve("bad.pdf");
        Files.writeString(file, "x");
        when(ingestionService.ingestFile(any(Path.class)))
                .thenThrow(new IngestionException("Failed to ingest: broken", new RuntimeException()));

        assertThat(tools.ingestDocument(file.toString())).startsWith("Error:").contains("broken");
    }

    @Test
    void removeReportsFoundAndNotFound() {
        UUID id = UUID.randomUUID();
        when(ingestionService.removeDocument(id)).thenReturn(true);
        assertThat(tools.removeDocument(id.toString())).contains("Removed");

        UUID unknown = UUID.randomUUID();
        when(ingestionService.removeDocument(unknown)).thenReturn(false);
        assertThat(tools.removeDocument(unknown.toString())).contains("No document");
    }

    @Test
    void removeRejectsInvalidUuid() {
        assertThat(tools.removeDocument("not-a-uuid")).startsWith("Error:");
    }
}
