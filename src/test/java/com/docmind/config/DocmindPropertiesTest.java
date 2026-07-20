package com.docmind.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocmindPropertiesTest {

    @Test
    void confluenceSpaceKeysDefaultsToEmptyWhenNull() {
        var confluence = new DocmindProperties.Confluence(
                "https://c.atlassian.net", "me@c.com", "tok", null, false, "PT1H");
        assertThat(confluence.spaceKeys()).isEmpty();
    }

    @Test
    void confluenceMayBeNullOnRoot() {
        var props = new DocmindProperties(null, false, 0.0, null);
        assertThat(props.confluence()).isNull();
        assertThat(props.docsDir()).isNotNull();
    }

    @Test
    void confluenceRetainsProvidedValues() {
        var props = new DocmindProperties(null, false, 0.0,
                new DocmindProperties.Confluence(
                        "https://c.atlassian.net", "me@c.com", "tok", List.of("DOCS", "ENG"), true, "PT2H"));
        assertThat(props.confluence().baseUrl()).isEqualTo("https://c.atlassian.net");
        assertThat(props.confluence().spaceKeys()).containsExactly("DOCS", "ENG");
    }
}
