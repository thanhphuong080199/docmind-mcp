package com.docmind.summary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatClientSummarizerTest {

    @Test
    void stripsThinkingBlocksAndTrims() {
        String raw = "<think>\nLet me reason about this...\n</think>\n\nSpring Boot simplifies setup.";
        assertThat(ChatClientSummarizer.stripThinking(raw)).isEqualTo("Spring Boot simplifies setup.");
    }

    @Test
    void passesPlainTextThrough() {
        assertThat(ChatClientSummarizer.stripThinking("Just a summary.")).isEqualTo("Just a summary.");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(ChatClientSummarizer.stripThinking(null)).isEmpty();
    }
}
