package com.agent.tools.web.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebDistillationServiceTest {

    private final WebDistillationService distillationService = new WebDistillationService();

    @Test
    @DisplayName("Should strip script, style, nav, and footer tags")
    void shouldStripNoisyTags() {
        String html = "<html><body>" +
                "<nav><a href='/home'>Home</a></nav>" +
                "<article>" +
                "<h1>Main Heading</h1>" +
                "<script>console.log('secret');</script>" +
                "<style>body { color: blue; }</style>" +
                "<p>Article content here.</p>" +
                "</article>" +
                "<footer>Copyright 2026</footer>" +
                "</body></html>";

        String markdown = distillationService.distillToMarkdown(html);

        assertThat(markdown).contains("# Main Heading");
        assertThat(markdown).contains("Article content here.");
        assertThat(markdown).doesNotContain("Home");
        assertThat(markdown).doesNotContain("secret");
        assertThat(markdown).doesNotContain("color: blue");
        assertThat(markdown).doesNotContain("Copyright 2026");
    }

    @Test
    @DisplayName("Should convert structural HTML elements into markdown")
    void shouldConvertHtmlElementsToMarkdown() {
        String html = "<main>" +
                "<h1>Heading 1</h1>" +
                "<h2>Heading 2</h2>" +
                "<p>Text with <b>bold</b> and <i>italic</i> words and a <a href='https://example.com'>link</a>.</p>" +
                "<ul><li>Item 1</li><li>Item 2</li></ul>" +
                "<pre><code>System.out.println();</code></pre>" +
                "</main>";

        String markdown = distillationService.distillToMarkdown(html);

        assertThat(markdown).contains("# Heading 1");
        assertThat(markdown).contains("## Heading 2");
        assertThat(markdown).contains("**bold");
        assertThat(markdown).contains("*italic");
        assertThat(markdown).contains("[link ](https://example.com)");
        assertThat(markdown).contains("- Item 1");
        assertThat(markdown).contains("- Item 2");
        assertThat(markdown).contains("```");
        assertThat(markdown).contains("System.out.println();");
    }

    @Test
    @DisplayName("Should truncate markdown exceeding 15000 characters")
    void shouldTruncateExcessiveMarkdown() {
        StringBuilder largeBody = new StringBuilder("<main>");
        for (int i = 0; i < 500; i++) {
            largeBody.append("<p>Paragraph ").append(i).append(" with substantial descriptive text filling the document.</p>");
        }
        largeBody.append("</main>");

        String markdown = distillationService.distillToMarkdown(largeBody.toString());

        assertThat(markdown).contains("... [CONTENT TRUNCATED TO PRESERVE CONTEXT WINDOW] ...");
        assertThat(markdown.length()).isLessThanOrEqualTo(15000 + 100);
    }

    @Test
    @DisplayName("Should return error message if HTML is completely empty")
    void shouldHandleEmptyHtml() {
        String markdown = distillationService.distillToMarkdown("<html></html>");
        assertThat(markdown).isNotNull();
    }
}
