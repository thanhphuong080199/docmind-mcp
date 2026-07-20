package com.docmind.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires docker-compose services running: docker compose up -d
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpEndpointSmokeTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void initializeHandshakeReturnsServerInfo() throws Exception {
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2025-06-18",
                  "capabilities":{},
                  "clientInfo":{"name":"smoke-test","version":"0.0.1"}}}
                """;
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                // Streamable-HTTP spec: client must accept both JSON and SSE
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(initialize))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("docmind");
        }
    }
}
