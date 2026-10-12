package com.agent.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebToolsTest {

    private static HttpServer server;
    private static int port;
    private final WebTools webTools = new WebTools();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        // 1. Endpoint returning clean HTML
        server.createContext("/clean", exchange -> {
            String html = "<html><head><title>Test Page</title><style>body { color: red; }</style></head>" +
                    "<body><h1>Heading</h1><p>This is clean text from a test webpage.</p>" +
                    "<script>alert('test');</script></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 2. Endpoint returning > 4000 characters
        server.createContext("/large", exchange -> {
            StringBuilder sb = new StringBuilder();
            sb.append("<html><body><p>");
            for (int i = 0; i < 300; i++) {
                sb.append("This is sentence number ").append(i).append(" with lots of words to exceed the character limit. ");
            }
            sb.append("</p></body></html>");
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Should extract clean text from HTML page ignoring tags and scripts")
    void shouldExtractCleanText() {
        String result = webTools.fetchWebPageText("http://localhost:" + port + "/clean");
        assertThat(result)
                .contains("Heading")
                .contains("This is clean text from a test webpage.")
                .doesNotContain("<p>")
                .doesNotContain("<script>")
                .doesNotContain("alert");
    }

    @Test
    @DisplayName("Should truncate text to 4000 characters and append warning marker")
    void shouldTruncateLargeWebpages() {
        String result = webTools.fetchWebPageText("http://localhost:" + port + "/large");
        assertThat(result).contains("[WEBPAGE TRUNCATED TO PRESERVE CONTEXT]");
        assertThat(result.length()).isLessThanOrEqualTo(4000 + "\n...[WEBPAGE TRUNCATED TO PRESERVE CONTEXT]".length() + 10);
    }

    @Test
    @DisplayName("Should validate empty URL input")
    void shouldHandleEmptyUrl() {
        assertThat(webTools.fetchWebPageText("")).isEqualTo("Error: URL cannot be empty.");
        assertThat(webTools.fetchWebPageText(null)).isEqualTo("Error: URL cannot be empty.");
    }

    @Test
    @DisplayName("Should return clean error message on unreachable URL")
    void shouldHandleConnectionError() {
        String result = webTools.fetchWebPageText("http://localhost:65530/unreachable");
        assertThat(result).startsWith("Error fetching URL:");
    }
}
