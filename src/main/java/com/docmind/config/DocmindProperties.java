package com.docmind.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docmind")
public record DocmindProperties(Path docsDir, boolean scanOnStartup, double similarityThreshold) {

    public DocmindProperties {
        if (docsDir == null) {
            docsDir = Path.of("docs-inbox");
        }
    }
}
