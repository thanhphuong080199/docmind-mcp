package com.docmind.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "docmind")
public record DocmindProperties(Path docsDir, boolean scanOnStartup, double similarityThreshold,
                                Confluence confluence) {

    public DocmindProperties {
        if (docsDir == null) {
            docsDir = Path.of("docs-inbox");
        }
    }

    public record Confluence(String baseUrl, String email, String apiToken,
                             List<String> spaceKeys, boolean syncEnabled, String syncInterval) {

        public Confluence {
            if (spaceKeys == null) {
                spaceKeys = List.of();
            }
        }
    }
}
