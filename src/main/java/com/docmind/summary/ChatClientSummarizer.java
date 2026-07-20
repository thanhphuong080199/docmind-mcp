package com.docmind.summary;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
class ChatClientSummarizer implements LlmSummarizer {

    private final ChatClient chatClient;

    ChatClientSummarizer(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public String summarize(String content) {
        String raw = chatClient.prompt()
                .system("""
                        You summarize technical documentation. Reply with a concise summary \
                        (at most 150 words) of the document excerpt you are given. \
                        Plain text only, no preamble. /no_think""")
                .user(content)
                .call()
                .content();
        return stripThinking(raw);
    }

    // qwen3 emits <think>...</think> reasoning blocks; /no_think reduces but does not guarantee absence
    static String stripThinking(String text) {
        return text == null ? "" : text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }
}
