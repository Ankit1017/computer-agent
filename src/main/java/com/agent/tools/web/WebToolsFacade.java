package com.agent.tools.web;

import com.agent.guardrails.GuardrailDecision;
import com.agent.guardrails.WebUrlPolicyGuard;
import com.agent.telemetry.SpanStatus;
import com.agent.telemetry.SwarmSpan;
import com.agent.telemetry.SwarmTelemetry;
import com.agent.telemetry.TelemetryAttributes;
import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.web.services.WebAnalysisService;
import com.agent.tools.web.services.WebDistillationService;
import com.agent.tools.web.services.WebNavigationService;
import com.agent.tools.web.services.WebSearchService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Map;

public class WebToolsFacade {
    private final SecurityInterceptor security;
    private final WebNavigationService navigationService;
    private final WebDistillationService distillationService;
    private final WebSearchService searchService;
    private final WebAnalysisService analysisService;

    public WebToolsFacade(SecurityInterceptor security, WebNavigationService navService, WebDistillationService distService) {
        this(security, navService, distService, new WebSearchService(), new WebAnalysisService());
    }

    public WebToolsFacade(SecurityInterceptor security, WebNavigationService navService, WebDistillationService distService, WebSearchService searchService) {
        this(security, navService, distService, searchService, new WebAnalysisService());
    }

    public WebToolsFacade(SecurityInterceptor security, WebNavigationService navService, WebDistillationService distService, WebSearchService searchService, WebAnalysisService analysisService) {
        this.security = security;
        this.navigationService = navService;
        this.distillationService = distService;
        this.searchService = searchService;
        this.analysisService = analysisService;
    }

    @Tool("Fetch a webpage and extract its core content as clean Markdown. Automatically strips ads and navigation menus.")
    public String readWebPage(@P("The full URL of the webpage to read") String url) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("readWebPage",
                com.agent.telemetry.SwarmTelemetry.formatArgs("url", url),
                () -> {
                    if (url == null || url.isBlank()) {
                        return "Error: URL cannot be blank.";
                    }
                    GuardrailDecision decision = WebUrlPolicyGuard.validateUrl(url);
                    if (decision.isBlocked()) {
                        SwarmSpan span = SwarmTelemetry.currentSpan();
                        if (span != null) {
                            span.setAttribute(TelemetryAttributes.GUARDRAIL_BLOCKED, true);
                            span.setAttribute(TelemetryAttributes.GUARDRAIL_REASON, decision.reason());
                            span.addEvent("guardrail.ssrf_blocked", Map.of(
                                    "url", url,
                                    "guardrail.reason", decision.reason() != null ? decision.reason() : "",
                                    "guardrail.message", decision.blockMessage() != null ? decision.blockMessage() : ""
                            ));
                            span.setStatus(SpanStatus.ERROR, decision.blockMessage());
                        }
                        return decision.blockMessage();
                    }
                    System.out.println("[WEB] Agent reading URL: " + url);
                    try {
                        String rawHtml = navigationService.fetchRawHtml(url);
                        String markdown = distillationService.distillToMarkdown(rawHtml);
                        return (markdown != null && !markdown.isBlank()) ? markdown : "No readable content found on page: " + url;
                    } catch (Exception e) {
                        return "Error reading webpage: " + e.getMessage();
                    }
                });
    }

    @Tool("Search the web for a query and return a list of relevant URLs and snippets.")
    public String searchWeb(@P("The query to search for") String query) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("searchWeb",
                com.agent.telemetry.SwarmTelemetry.formatArgs("query", query),
                () -> {
                    if (query == null || query.isBlank()) {
                        return "Error: Search query cannot be blank.";
                    }
                    System.out.println("[WEB] Agent searching for: " + query);
                    String results = searchService.search(query);
                    return (results != null && !results.isBlank()) ? results : "No search results found for: " + query;
                });
    }

    @Tool("Click an HTML element on the currently active web page using a CSS selector (e.g., 'button#submit-btn' or '.login-link').")
    public String clickWebElement(@P("The CSS selector of the element to click") String cssSelector) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("clickWebElement",
                com.agent.telemetry.SwarmTelemetry.formatArgs("cssSelector", cssSelector),
                () -> {
                    if (cssSelector == null || cssSelector.isBlank()) {
                        return "Error: CSS selector cannot be blank.";
                    }
                    if (!security.requestPermission("click web element via Playwright", cssSelector)) {
                        return "ACTION REJECTED: User explicitly denied the web click. Do not retry this action.";
                    }
                    String result = navigationService.clickElement(cssSelector);
                    return (result != null && !result.isBlank()) ? result : "Clicked element matching selector: " + cssSelector;
                });
    }

    @Tool("Fill text into an HTML input field on the currently active web page using a CSS selector.")
    public String fillWebInput(
            @P("The CSS selector of the input field (e.g., 'input[name=username]')") String cssSelector,
            @P("The text to type into the field") String text) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("fillWebInput",
                com.agent.telemetry.SwarmTelemetry.formatArgs("cssSelector", cssSelector, "text", text),
                () -> {
                    if (cssSelector == null || cssSelector.isBlank()) {
                        return "Error: CSS selector cannot be blank.";
                    }
                    String txt = text != null ? text : "";
                    if (!security.requestPermission("fill web input via Playwright", cssSelector + " with text: " + txt)) {
                        return "ACTION REJECTED: User explicitly denied filling the input. Do not retry this action.";
                    }
                    String result = navigationService.fillInput(cssSelector, txt);
                    return (result != null && !result.isBlank()) ? result : "Filled text into selector: " + cssSelector;
                });
    }

    @Tool("Extracts the core Markdown content of the currently active web page without navigating to a new URL. Useful after clicking a button that changes the page content dynamically.")
    public String readCurrentWebPage() {
        return com.agent.telemetry.SwarmTelemetry.executeTool("readCurrentWebPage", "{}", () -> {
            System.out.println("[WEB] Agent reading current active page DOM.");
            try {
                String rawHtml = navigationService.getCurrentHtml();
                if (rawHtml == null || rawHtml.isBlank()) {
                    return "The active web page is empty. Use readWebPage to navigate to a URL first.";
                }
                String markdown = distillationService.distillToMarkdown(rawHtml);
                return (markdown != null && !markdown.isBlank()) ? markdown : "No readable content found on the currently active web page.";
            } catch (Exception e) {
                return "Error reading current webpage: " + e.getMessage();
            }
        });
    }

    @Tool("Analyze a web page by fetching, distilling, and performing on-the-fly RAG semantic search to answer a specific query without overloading context.")
    public String analyzeWebPage(
            @P("The full URL of the webpage to analyze") String url,
            @P("The query or question to answer using the page content") String query) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("analyzeWebPage",
                com.agent.telemetry.SwarmTelemetry.formatArgs("url", url, "query", query),
                () -> {
                    if (url == null || url.isBlank()) {
                        return "Error: URL cannot be blank.";
                    }
                    if (query == null || query.isBlank()) {
                        return "Error: Query cannot be blank.";
                    }
                    GuardrailDecision decision = WebUrlPolicyGuard.validateUrl(url);
                    if (decision.isBlocked()) {
                        SwarmSpan span = SwarmTelemetry.currentSpan();
                        if (span != null) {
                            span.setAttribute(TelemetryAttributes.GUARDRAIL_BLOCKED, true);
                            span.setAttribute(TelemetryAttributes.GUARDRAIL_REASON, decision.reason());
                            span.addEvent("guardrail.ssrf_blocked", Map.of(
                                    "url", url,
                                    "guardrail.reason", decision.reason() != null ? decision.reason() : "",
                                    "guardrail.message", decision.blockMessage() != null ? decision.blockMessage() : ""
                            ));
                            span.setStatus(SpanStatus.ERROR, decision.blockMessage());
                        }
                        return decision.blockMessage();
                    }
                    System.out.println("[WEB] Agent analyzing URL: " + url + " for query: " + query);
                    try {
                        String rawHtml = navigationService.fetchRawHtml(url);
                        String markdown = distillationService.distillToMarkdown(rawHtml);
                        if (markdown == null || markdown.isBlank()) {
                            return "No readable content found on page to analyze: " + url;
                        }
                        String analysis = analysisService.analyzeContent(markdown, query);
                        return (analysis != null && !analysis.isBlank()) ? analysis : "Analysis returned no results for query: " + query;
                    } catch (Exception e) {
                        return "Error analyzing webpage: " + e.getMessage();
                    }
                });
    }
}
