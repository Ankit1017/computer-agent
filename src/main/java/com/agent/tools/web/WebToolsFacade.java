package com.agent.tools.web;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.web.services.WebDistillationService;
import com.agent.tools.web.services.WebNavigationService;
import com.agent.tools.web.services.WebSearchService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class WebToolsFacade {
    private final SecurityInterceptor security;
    private final WebNavigationService navigationService;
    private final WebDistillationService distillationService;
    private final WebSearchService searchService;

    public WebToolsFacade(SecurityInterceptor security, WebNavigationService navService, WebDistillationService distService) {
        this(security, navService, distService, new WebSearchService());
    }

    public WebToolsFacade(SecurityInterceptor security, WebNavigationService navService, WebDistillationService distService, WebSearchService searchService) {
        this.security = security;
        this.navigationService = navService;
        this.distillationService = distService;
        this.searchService = searchService;
    }

    @Tool("Fetch a webpage and extract its core content as clean Markdown. Automatically strips ads and navigation menus.")
    public String readWebPage(@P("The full URL of the webpage to read") String url) {
        if (url == null || url.isBlank()) {
            return "Error: URL cannot be blank.";
        }
        System.out.println("[WEB] Agent reading URL: " + url);
        
        try {
            String rawHtml = navigationService.fetchRawHtml(url);
            String markdown = distillationService.distillToMarkdown(rawHtml);
            return (markdown != null && !markdown.isBlank()) ? markdown : "No readable content found on page: " + url;
        } catch (Exception e) {
            return "Error reading webpage: " + e.getMessage();
        }
    }

    @Tool("Search the web for a query and return a list of relevant URLs and snippets.")
    public String searchWeb(@P("The query to search for") String query) {
        if (query == null || query.isBlank()) {
            return "Error: Search query cannot be blank.";
        }
        System.out.println("[WEB] Agent searching for: " + query);
        String results = searchService.search(query);
        return (results != null && !results.isBlank()) ? results : "No search results found for: " + query;
    }

    @Tool("Click an HTML element on the currently active web page using a CSS selector (e.g., 'button#submit-btn' or '.login-link').")
    public String clickWebElement(@P("The CSS selector of the element to click") String cssSelector) {
        if (cssSelector == null || cssSelector.isBlank()) {
            return "Error: CSS selector cannot be blank.";
        }
        if (!security.requestPermission("click web element via Playwright", cssSelector)) {
            return "ACTION REJECTED: User explicitly denied the web click. Do not retry this action.";
        }
        String result = navigationService.clickElement(cssSelector);
        return (result != null && !result.isBlank()) ? result : "Clicked element matching selector: " + cssSelector;
    }

    @Tool("Fill text into an HTML input field on the currently active web page using a CSS selector.")
    public String fillWebInput(
            @P("The CSS selector of the input field (e.g., 'input[name=username]')") String cssSelector,
            @P("The text to type into the field") String text) {
        if (cssSelector == null || cssSelector.isBlank()) {
            return "Error: CSS selector cannot be blank.";
        }
        if (text == null) {
            text = "";
        }
        if (!security.requestPermission("fill web input via Playwright", cssSelector + " with text: " + text)) {
            return "ACTION REJECTED: User explicitly denied filling the input. Do not retry this action.";
        }
        String result = navigationService.fillInput(cssSelector, text);
        return (result != null && !result.isBlank()) ? result : "Filled text into selector: " + cssSelector;
    }

    @Tool("Extracts the core Markdown content of the currently active web page without navigating to a new URL. Useful after clicking a button that changes the page content dynamically.")
    public String readCurrentWebPage() {
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
    }
}
