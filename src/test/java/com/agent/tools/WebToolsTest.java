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
import java.util.Scanner;

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

        // 3. API GET endpoint
        server.createContext("/api/get", exchange -> {
            byte[] bytes = "{\"status\":\"success\",\"items\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 4. API POST endpoint
        server.createContext("/api/post", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
            String response = "{\"created\":true,\"auth\":\"" + (authHeader != null ? authHeader : "") + "\",\"echo\":" + requestBody + "}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        // 5. API Large payload endpoint (> 4000 chars)
        server.createContext("/api/large", exchange -> {
            StringBuilder sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < 500; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"id\":").append(i).append(",\"title\":\"Item ").append(i).append(" with verbose metadata\"}");
            }
            sb.append("]}");
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
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

    @Test
    @DisplayName("Should execute GET request autonomously without prompting user")
    void shouldExecuteGetRequestAutonomously() {
        String response = webTools.sendHttpRequest("http://localhost:" + port + "/api/get", "GET", "", "");
        assertThat(response)
                .startsWith("HTTP 200")
                .contains("\"status\":\"success\"")
                .contains("\"items\":[1,2,3]");
    }

    @Test
    @DisplayName("Should execute POST request when user grants HITL approval (Y)")
    void shouldExecutePostRequestWhenApproved() {
        Scanner approvingScanner = new Scanner("Y\n");
        WebTools approvingTools = new WebTools(approvingScanner);

        String headers = "{\"Authorization\": \"Bearer token123\", \"Content-Type\": \"application/json\"}";
        String payload = "{\"name\":\"agent\",\"action\":\"ping\"}";

        String response = approvingTools.sendHttpRequest(
                "http://localhost:" + port + "/api/post", "POST", headers, payload);

        assertThat(response)
                .startsWith("HTTP 201")
                .contains("\"created\":true")
                .contains("Bearer token123")
                .contains("\"name\":\"agent\"");
    }

    @Test
    @DisplayName("Should intercept and abort POST request when user denies HITL prompt (N)")
    void shouldDenyPostRequestWhenRejected() {
        Scanner rejectingScanner = new Scanner("N\n");
        WebTools rejectingTools = new WebTools(rejectingScanner);

        String response = rejectingTools.sendHttpRequest(
                "http://localhost:" + port + "/api/post", "POST", "", "{\"test\":true}");

        assertThat(response).isEqualTo("User denied HTTP request.");
    }

    @Test
    @DisplayName("Should truncate API responses exceeding 4000 characters")
    void shouldTruncateLargeApiResponse() {
        String response = webTools.sendHttpRequest("http://localhost:" + port + "/api/large", "GET", "", "");
        assertThat(response)
                .startsWith("HTTP 200")
                .contains("[RESPONSE TRUNCATED TO PRESERVE CONTEXT]");
        assertThat(response.length()).isLessThanOrEqualTo(4000 + "\n...[RESPONSE TRUNCATED TO PRESERVE CONTEXT]".length() + 20);
    }

    @Test
    @DisplayName("Should reject empty or null URL for sendHttpRequest")
    void shouldRejectEmptyUrlForHttpRequest() {
        assertThat(webTools.sendHttpRequest("", "GET", "", "")).isEqualTo("Error: URL cannot be empty.");
        assertThat(webTools.sendHttpRequest(null, "GET", "", "")).isEqualTo("Error: URL cannot be empty.");
    }
}
