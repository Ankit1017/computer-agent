package com.agent.tools.web.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchServiceTest {

    private WebSearchService searchService;

    @BeforeEach
    void setUp() {
        searchService = new WebSearchService();
    }

    @Test
    @DisplayName("Should return no results message when document contains no result elements")
    void shouldReturnNoResultsWhenEmpty() {
        Document emptyDoc = Jsoup.parse("<html><body><div class='no-results'>Nothing</div></body></html>");
        String result = searchService.parseResults(emptyDoc, "Unknown Query");

        assertThat(result).isEqualTo("No search results found for: Unknown Query");
    }

    @Test
    @DisplayName("Should parse search results, clean DuckDuckGo redirect URLs, and format Markdown")
    void shouldParseSearchResultsAndCleanUrls() {
        String html = """
                <html><body>
                  <div class="result">
                    <h2 class="result__title">
                      <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.oracle.com%2Fvirtual-threads&rut=xyz">Virtual Threads Docs</a>
                    </h2>
                    <a class="result__url" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.oracle.com%2Fvirtual-threads&rut=xyz">docs.oracle.com</a>
                    <a class="result__snippet">Official documentation for lightweight threads in Java.</a>
                  </div>
                </body></html>
                """;

        Document doc = Jsoup.parse(html);
        String markdown = searchService.parseResults(doc, "Java Virtual Threads");

        assertThat(markdown).contains("Search Results for 'Java Virtual Threads':");
        assertThat(markdown).contains("### 1. Virtual Threads Docs");
        assertThat(markdown).contains("- **URL:** `https://docs.oracle.com/virtual-threads`");
        assertThat(markdown).contains("- **Snippet:** Official documentation for lightweight threads in Java.");
    }

    @Test
    @DisplayName("Should limit extracted results to a maximum of 5 items")
    void shouldLimitResultsToMaxFive() {
        StringBuilder htmlBuilder = new StringBuilder("<html><body>");
        for (int i = 1; i <= 8; i++) {
            htmlBuilder.append("""
                    <div class="result">
                      <h2 class="result__title"><a class="result__a" href="https://example.com/page%d">Title %d</a></h2>
                      <a class="result__url" href="https://example.com/page%d">example.com</a>
                      <div class="result__snippet">Snippet %d</div>
                    </div>
                    """.formatted(i, i, i, i));
        }
        htmlBuilder.append("</body></html>");

        Document doc = Jsoup.parse(htmlBuilder.toString());
        String markdown = searchService.parseResults(doc, "Multi items");

        assertThat(markdown).contains("### 1. Title 1");
        assertThat(markdown).contains("### 5. Title 5");
        assertThat(markdown).doesNotContain("### 6. Title 6");
    }

    @Test
    @DisplayName("Should handle search gracefully without throwing unhandled exceptions")
    void shouldHandleSearchGracefully() {
        WebSearchService service = new WebSearchService();
        // search with invalid or mock search that might fail or succeed
        String result = service.search("java");
        assertThat(result).isNotNull();
    }
}
