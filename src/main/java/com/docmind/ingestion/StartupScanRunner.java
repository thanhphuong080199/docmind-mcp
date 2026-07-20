package com.docmind.ingestion;

import com.docmind.config.DocmindProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "docmind.scan-on-startup", havingValue = "true")
public class StartupScanRunner implements ApplicationRunner {

    private final DocsFolderScanner scanner;
    private final DocmindProperties properties;

    public StartupScanRunner(DocsFolderScanner scanner, DocmindProperties properties) {
        this.scanner = scanner;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        scanner.scan(properties.docsDir());
    }
}
