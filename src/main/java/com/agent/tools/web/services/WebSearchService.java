package com.agent.tools.web.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class WebSearchService {

    public String search(String query) {
        if (query == null || query.isBlank()) {
            return "Error: Search query cannot be null or blank.";
        }
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            // Use DuckDuckGo's HTML fallback for easy scraping without API keys
            String searchUrl = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

            Document doc = Jsoup.connect(searchUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(10000)
                    .get();

            return parseResults(doc, query);
        } catch (Exception e) {
            return "Error performing web search: " + e.getMessage();
        }
    }

    public String parseResults(Document doc, String query) {
        Elements results = doc.select(".result");
        if (results.isEmpty()) {
            return "No search results found for: " + query;
        }

        StringBuilder sb = new StringBuilder("Search Results for '" + query + "':\n\n");
        int count = 0;

        for (Element result : results) {
            if (count >= 5) break; // Limit to top 5 results to protect context

            Element titleElement = result.selectFirst(".result__title > a");
            if (titleElement == null) {
                titleElement = result.selectFirst(".result__a");
            }
            Element snippetElement = result.selectFirst(".result__snippet");
            Element urlElement = result.selectFirst(".result__url");

            if (titleElement != null && (urlElement != null || titleElement.hasAttr("href"))) {
                String title = com.agent.guardrails.UntrustedContentSanitizer.sanitizeText(titleElement.text());
                String url = urlElement != null ? urlElement.attr("href") : titleElement.attr("href");

                // DuckDuckGo sometimes prepends redirect URLs. Clean it up if necessary.
                if (url.startsWith("//duckduckgo.com/l/?uddg=") || url.contains("uddg=")) {
                    url = java.net.URLDecoder.decode(url.substring(url.indexOf("uddg=") + 5), StandardCharsets.UTF_8);
                    int ampIndex = url.indexOf("&rut=");
                    if (ampIndex > 0) {
                        url = url.substring(0, ampIndex);
                    } else {
                        int generalAmp = url.indexOf('&');
                        if (generalAmp > 0) {
                            url = url.substring(0, generalAmp);
                        }
                    }
                } else if (url.startsWith("//")) {
                    url = "https:" + url;
                }

                String snippet = snippetElement != null ? com.agent.guardrails.UntrustedContentSanitizer.sanitizeText(snippetElement.text()) : "No snippet available.";

                sb.append("### ").append(count + 1).append(". ").append(title).append("\n");
                sb.append("- **URL:** `").append(url).append("`\n");
                sb.append("- **Snippet:** ").append(snippet).append("\n\n");
                count++;
            }
        }
        return sb.toString().trim();
    }
}
