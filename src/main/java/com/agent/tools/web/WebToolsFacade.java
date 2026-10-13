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
        // Read operations generally don't require HITL security gating, but we log it.
        System.out.println("[WEB] Agent reading URL: " + url);
        
        try {
            String rawHtml = navigationService.fetchRawHtml(url);
            return distillationService.distillToMarkdown(rawHtml);
        } catch (Exception e) {
            return "Error reading webpage: " + e.getMessage();
        }
    }

    @Tool("Search the web for a query and return a list of relevant URLs and snippets.")
    public String searchWeb(@P("The query to search for") String query) {
        System.out.println("[WEB] Agent searching for: " + query);
        return searchService.search(query);
    }
}
