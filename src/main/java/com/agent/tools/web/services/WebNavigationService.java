package com.agent.tools.web.services;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitUntilState;

public class WebNavigationService implements AutoCloseable {
    private Playwright playwright;
    private Browser browser;

    public WebNavigationService() {
        System.out.println("[WEB] Initializing Headless Chromium Engine (Playwright)...");
        this.playwright = Playwright.create();
        // Launch headless chromium
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        System.out.println("[WEB] Headless Browser Engine Ready.");
    }

    public WebNavigationService(Browser browser) {
        this.browser = browser;
        this.playwright = null;
    }

    public String fetchRawHtml(String urlString) {
        if (!urlString.startsWith("http")) {
            urlString = "https://" + urlString;
        }

        try (Page page = browser.newPage()) {
            // Navigate and wait for the network to become mostly idle (JS loaded)
            try {
                page.navigate(urlString, new Page.NavigateOptions()
                        .setTimeout(15000) // 15 second timeout
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));
            } catch (TimeoutError te) {
                String content = page.content();
                if (content != null && !content.isBlank() && !content.equals("<html><head></head><body></body></html>")) {
                    return content;
                }
                throw te;
            }

            return page.content();
        } catch (Exception e) {
            throw new RuntimeException("Error rendering page via Playwright: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (browser != null) {
            System.out.println("[WEB] Closing Headless Browser...");
            try {
                browser.close();
            } catch (Exception ignored) {}
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {}
        }
    }
}
