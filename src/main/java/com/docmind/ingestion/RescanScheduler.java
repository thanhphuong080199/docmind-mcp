package com.docmind.ingestion;

import com.docmind.config.DocmindProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.rescan-enabled", havingValue = "true")
public class RescanScheduler {

    private final DocsFolderScanner scanner;
    private final DocmindProperties properties;

    public RescanScheduler(DocsFolderScanner scanner, DocmindProperties properties) {
        this.scanner = scanner;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${docmind.rescan-interval:PT10M}")
    public void rescan() {
        scanner.scan(properties.docsDir());
    }
}
