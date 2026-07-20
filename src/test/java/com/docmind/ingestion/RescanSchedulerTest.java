package com.docmind.ingestion;

import java.nio.file.Path;

import com.docmind.config.DocmindProperties;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RescanSchedulerTest {

    @Test
    void rescanDelegatesToScannerWithConfiguredFolder() {
        DocsFolderScanner scanner = mock(DocsFolderScanner.class);
        DocmindProperties properties = new DocmindProperties(Path.of("some-dir"), false, 0.0, null);

        new RescanScheduler(scanner, properties).rescan();

        verify(scanner).scan(Path.of("some-dir"));
    }
}
