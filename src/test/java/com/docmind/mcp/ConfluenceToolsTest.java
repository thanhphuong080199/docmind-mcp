package com.docmind.mcp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.docmind.confluence.ConfluenceException;
import com.docmind.confluence.ConfluenceSyncService;
import com.docmind.confluence.ConfluenceSyncService.SyncResult;
import com.docmind.domain.DocumentSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfluenceToolsTest {

    @Mock
    ConfluenceSyncService syncService;

    @InjectMocks
    ConfluenceTools tools;

    @Test
    void syncOneSpaceFormatsCounts() {
        when(syncService.sync("DOCS")).thenReturn(new SyncResult("DOCS", 3, 39, 1, 2));

        assertThat(tools.syncConfluence("DOCS"))
                .isEqualTo("Synced space DOCS: 3 ingested, 39 skipped, 1 failed, 2 removed");
    }

    @Test
    void syncNoArgSyncsEveryConfiguredSpace() {
        when(syncService.configuredSpaceKeys()).thenReturn(List.of("DOCS", "ENG"));
        when(syncService.sync("DOCS")).thenReturn(new SyncResult("DOCS", 1, 0, 0, 0));
        when(syncService.sync("ENG")).thenReturn(new SyncResult("ENG", 2, 0, 0, 0));

        String out = tools.syncConfluence(null);

        assertThat(out).contains("Synced space DOCS").contains("Synced space ENG");
    }

    @Test
    void syncErrorReturnsStructuredStringNotStackTrace() {
        when(syncService.sync("NOPE")).thenThrow(new ConfluenceException("Confluence space not found: NOPE"));

        assertThat(tools.syncConfluence("NOPE")).startsWith("Error:").contains("NOPE");
    }

    @Test
    void ingestPageFormatsSuccess() {
        UUID id = UUID.randomUUID();
        when(syncService.ingestPage("456")).thenReturn(new DocumentSource(
                id, "Deploy Guide", "https://c.atlassian.net/wiki/spaces/DOCS/pages/456",
                "CONFLUENCE", "abc", 4, null, "INGESTED", Instant.now()));

        assertThat(tools.ingestConfluencePage("456"))
                .contains(id.toString()).contains("4 chunks").contains("INGESTED");
    }

    @Test
    void ingestPageErrorReturnsStructuredString() {
        when(syncService.ingestPage("999")).thenThrow(new ConfluenceException("Confluence page not found: 999"));

        assertThat(tools.ingestConfluencePage("999")).startsWith("Error:").contains("999");
    }
}
