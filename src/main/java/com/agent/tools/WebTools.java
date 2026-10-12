package com.agent.tools;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;

/**
 * Dedicated web and API tools for the autonomous agent.
 * Supports HTML webpage scraping with JSoup and raw HTTP REST API calls via HttpClient,
 * enforcing Human-In-The-Loop (HITL) authorization for mutating requests (POST, PUT, DELETE)
 * and strict character truncation on returned payloads.
 */
public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);
    private static final int MAX_TEXT_CHARACTERS = 4000;
    private static final int TIMEOUT_MILLIS = 10000;
    private static final Duration HTTP_CLIENT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);

    private final Scanner consoleScanner;
    private final HttpClient httpClient;

    public WebTools() {
        this(new Scanner(System.in));
    }

    public WebTools(Scanner consoleScanner) {
        this(consoleScanner, HttpClient.newBuilder()
                .connectTimeout(HTTP_CLIENT_TIMEOUT)
                .build());
    }

    public WebTools(Scanner consoleScanner, HttpClient httpClient) {
        this.consoleScanner = consoleScanner;
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    /**
     * Fetches a webpage by URL and returns its clean text content.
     * Truncates text exceeding 4,000 characters to prevent LLM context saturation.
     *
     * @param url target webpage URL
     * @return clean, human-readable text or error message
     */
    @Tool("Fetch a webpage by URL and return its clean text content. Call this to read any URL, Wikipedia page, documentation, or online article.")
    public String fetchWebPageText(@P("The HTTP or HTTPS URL to read") String url) {
        if (url == null || url.isBlank()) {
            return "Error: URL cannot be empty.";
        }

        String targetUrl = url.trim();
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://" + targetUrl;
        }

        log.info("Tool Call: fetchWebPageText(url='{}')", targetUrl);
        System.out.println("\n[WEB] Fetching webpage: " + targetUrl);
        System.out.flush();

        try {
            Document doc = Jsoup.connect(targetUrl)
                    .userAgent(DEFAULT_USER_AGENT)
                    .timeout(TIMEOUT_MILLIS)
                    .get();

            String text = doc.text();
            if (text == null || text.isBlank()) {
                return "[Webpage returned empty text content]";
            }

            text = text.trim();
            if (text.length() > MAX_TEXT_CHARACTERS) {
                return text.substring(0, MAX_TEXT_CHARACTERS) + "\n...[WEBPAGE TRUNCATED TO PRESERVE CONTEXT]";
            }

            return text;

        } catch (IOException e) {
            log.error("Failed to fetch URL '{}': {}", targetUrl, e.getMessage());
            return "Error fetching URL: " + e.getMessage();
        } catch (Exception e) {
            log.error("Unexpected error fetching URL '{}'", targetUrl, e);
            return "Error fetching URL: " + e.getMessage();
        }
    }

    /**
     * Sends an HTTP request to a REST API. Supports GET, POST, PUT, DELETE.
     * Mutating methods (POST, PUT, DELETE) require explicit HITL approval.
     * Response payload is truncated to 4,000 characters to protect LLM context windows.
     *
     * @param url         target API endpoint URL
     * @param method      HTTP method (GET, POST, PUT, DELETE)
     * @param headersJson optional JSON string of headers, e.g. {"Authorization":"Bearer token"}
     * @param body        optional JSON payload for POST/PUT
     * @return HTTP status code and truncated response body, or denial message
     */
    @Tool("Send an HTTP request to a REST API. Supports GET, POST, PUT, DELETE. Headers and body must be valid JSON strings, or empty strings if not needed.")
    public String sendHttpRequest(
            @P("URL") String url,
            @P("HTTP method: GET, POST, PUT, or DELETE") String method,
            @P("JSON string of headers, e.g. {'Authorization':'Bearer token'}, or empty string") String headersJson,
            @P("JSON payload for POST/PUT, or empty string") String body) {

        if (url == null || url.isBlank()) {
            return "Error: URL cannot be empty.";
        }

        String targetUrl = url.trim();
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://" + targetUrl;
        }

        String upperMethod = method != null && !method.isBlank() ? method.trim().toUpperCase() : "GET";
        log.info("Tool Call: sendHttpRequest(method='{}', url='{}')", upperMethod, targetUrl);

        // HITL Interceptor: prompt for mutating requests (POST, PUT, DELETE)
        if (!"GET".equalsIgnoreCase(upperMethod)) {
            log.warn("HITL Intercept: Agent requested to send a {} request to {}", upperMethod, targetUrl);
            System.out.println("\n[SECURITY] Agent requested to send a " + upperMethod + " request to " + targetUrl);
            System.out.print("Allow request? (Y/N): ");
            System.out.flush();

            String confirmation = "";
            if (consoleScanner != null && consoleScanner.hasNextLine()) {
                confirmation = consoleScanner.nextLine().trim();
            }

            if (!"Y".equalsIgnoreCase(confirmation)) {
                log.info("HITL Intercept: User rejected HTTP request ('{}')", confirmation);
                return "User denied HTTP request.";
            }

            log.info("HITL Intercept: User approved HTTP request. Executing...");
        }

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(HTTP_REQUEST_TIMEOUT);

            // Parse and attach headers
            if (headersJson != null && !headersJson.isBlank()) {
                try {
                    Map<String, Object> headers = objectMapper.readValue(headersJson.trim(),
                            new TypeReference<Map<String, Object>>() {});
                    for (Map.Entry<String, Object> entry : headers.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            reqBuilder.header(entry.getKey().trim(), String.valueOf(entry.getValue()).trim());
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse headers JSON '{}': {}", headersJson, e.getMessage());
                }
            }

            // Attach body publisher
            HttpRequest.BodyPublisher bodyPublisher = (body != null && !body.isBlank())
                    ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody();

            switch (upperMethod) {
                case "GET" -> reqBuilder.GET();
                case "DELETE" -> reqBuilder.DELETE();
                case "POST" -> reqBuilder.POST(bodyPublisher);
                case "PUT" -> reqBuilder.PUT(bodyPublisher);
                default -> reqBuilder.method(upperMethod, bodyPublisher);
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            String responseBody = response.body() != null ? response.body().trim() : "";
            if (responseBody.length() > MAX_TEXT_CHARACTERS) {
                responseBody = responseBody.substring(0, MAX_TEXT_CHARACTERS)
                        + "\n...[RESPONSE TRUNCATED TO PRESERVE CONTEXT]";
            }

            return "HTTP " + response.statusCode() + "\n" + responseBody;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("HTTP request interrupted for '{}'", targetUrl, e);
            return "Error: HTTP request interrupted: " + e.getMessage();
        } catch (Exception e) {
            log.error("Failed to execute HTTP request to '{}'", targetUrl, e);
            return "Error executing HTTP request: " + e.getMessage();
        }
    }
}
