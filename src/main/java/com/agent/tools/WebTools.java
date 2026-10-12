package com.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Dedicated web inspection and scraping tools for the autonomous agent.
 * Extracts clean, human-readable text from HTML pages while enforcing
 * strict character truncation to protect LLM context windows.
 */
public class WebTools {

    private static final Logger log = LoggerFactory.getLogger(WebTools.class);
    private static final int MAX_TEXT_CHARACTERS = 4000;
    private static final int TIMEOUT_MILLIS = 10000;
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

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
}
