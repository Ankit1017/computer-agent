package com.agent.tools.web.services;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

public class WebDistillationService {
    private static final int MAX_MARKDOWN_LENGTH = 15000;

    public String distillToMarkdown(String rawHtml) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "Error: HTML content cannot be empty.";
        }

        Document doc = Jsoup.parse(rawHtml);

        // 1. Strip noisy elements
        doc.select("script, style, nav, footer, aside, header, meta, link, iframe, noscript").remove();

        // 2. Try to find the main content area, fallback to body
        Element mainContent = doc.selectFirst("article, main, [role=main], .content, #content, .post, #post");
        if (mainContent == null) {
            mainContent = doc.body();
        }

        if (mainContent == null) {
            return "Error: Could not locate readable content on this page.";
        }

        // 3. Convert to Markdown
        StringBuilder markdown = new StringBuilder();
        convertToMarkdown(mainContent, markdown);

        String finalMarkdown = markdown.toString().trim();
        if (finalMarkdown.isBlank()) {
            return "No readable content found on this page.";
        }
        
        // 4. Enforce Token/Context limit
        if (finalMarkdown.length() > MAX_MARKDOWN_LENGTH) {
            finalMarkdown = finalMarkdown.substring(0, MAX_MARKDOWN_LENGTH) + "\n\n... [CONTENT TRUNCATED TO PRESERVE CONTEXT WINDOW] ...";
        }

        return finalMarkdown;
    }

    private void convertToMarkdown(Node node, StringBuilder sb) {
        if (node instanceof TextNode textNode) {
            String text = textNode.text().replaceAll("\\s+", " ");
            if (!text.isBlank()) {
                sb.append(text).append(" ");
            }
        } else if (node instanceof Element element) {
            String tagName = element.tagName().toLowerCase();
            boolean isBlock = false;

            switch (tagName) {
                case "h1" -> { sb.append("\n# "); isBlock = true; }
                case "h2" -> { sb.append("\n## "); isBlock = true; }
                case "h3" -> { sb.append("\n### "); isBlock = true; }
                case "h4", "h5", "h6" -> { sb.append("\n#### "); isBlock = true; }
                case "p", "div", "section" -> { sb.append("\n\n"); isBlock = true; }
                case "br" -> sb.append("\n");
                case "b", "strong" -> sb.append("**");
                case "i", "em" -> sb.append("*");
                case "a" -> sb.append("[");
                case "li" -> { sb.append("\n- "); isBlock = true; }
                case "code" -> sb.append("`");
                case "pre" -> { sb.append("\n```\n"); isBlock = true; }
            }

            for (Node child : element.childNodes()) {
                convertToMarkdown(child, sb);
            }

            switch (tagName) {
                case "b", "strong" -> sb.append("** ");
                case "i", "em" -> sb.append("* ");
                case "a" -> {
                    String href = element.attr("abs:href");
                    if (href.isEmpty()) href = element.attr("href");
                    sb.append("](").append(href).append(") ");
                }
                case "code" -> sb.append("` ");
                case "pre" -> sb.append("\n```\n");
            }
            if (isBlock) sb.append("\n");
        }
    }
}
