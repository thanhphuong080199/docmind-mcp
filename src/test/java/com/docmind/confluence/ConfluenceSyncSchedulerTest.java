package com.docmind.confluence;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfluenceSyncSchedulerTest {

    @Test
    void syncAllDelegatesToServiceForEachConfiguredSpace() {
        ConfluenceSyncService service = mock(ConfluenceSyncService.class);
        when(service.configuredSpaceKeys()).thenReturn(List.of("DOCS", "ENG"));

        new ConfluenceSyncScheduler(service).syncAll();

        verify(service).sync("DOCS");
        verify(service).sync("ENG");
    }

    @Test
    void oneSpaceFailingDoesNotStopTheOthers() {
        ConfluenceSyncService service = mock(ConfluenceSyncService.class);
        when(service.configuredSpaceKeys()).thenReturn(List.of("DOCS", "ENG"));
        when(service.sync("DOCS")).thenThrow(new ConfluenceException("boom"));

        new ConfluenceSyncScheduler(service).syncAll();

        verify(service).sync("ENG");
    }
}
