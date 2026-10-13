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
    private Page activePage;

    public WebNavigationService() {
        System.out.println("[WEB] Initializing Headless Chromium Engine (Playwright)...");
        this.playwright = Playwright.create();
        this.browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        this.activePage = browser.newPage();
        System.out.println("[WEB] Headless Browser Engine Ready.");
    }

    public WebNavigationService(Browser browser, Page page) {
        this.browser = browser;
        this.activePage = page;
        this.playwright = null;
    }

    public WebNavigationService(Page page) {
        this.browser = null;
        this.activePage = page;
        this.playwright = null;
    }

    public String fetchRawHtml(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new IllegalArgumentException("URL cannot be null or blank.");
        }

        String targetUrl = urlString.trim();
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://" + targetUrl;
        }

        try {
            try {
                activePage.navigate(targetUrl, new Page.NavigateOptions()
                        .setTimeout(15000)
                        .setWaitUntil(WaitUntilState.NETWORKIDLE));
            } catch (TimeoutError te) {
                String content = activePage.content();
                if (content != null && !content.isBlank() && !content.equals("<html><head></head><body></body></html>")) {
                    return content;
                }
                throw te;
            }
            return activePage.content();
        } catch (Exception e) {
            throw new RuntimeException("Error rendering page via Playwright: " + e.getMessage(), e);
        }
    }

    public String clickElement(String cssSelector) {
        if (cssSelector == null || cssSelector.isBlank()) {
            return "Error: CSS selector cannot be blank.";
        }
        if (activePage == null) {
            return "Error: Headless browser page is not initialized.";
        }
        try {
            activePage.locator(cssSelector.trim()).first().click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000));
            // Wait briefly for any JS transitions
            activePage.waitForTimeout(1000); 
            return "Successfully clicked element matching selector: " + cssSelector;
        } catch (Exception e) {
            return "Error clicking element: " + e.getMessage();
        }
    }

    public String fillInput(String cssSelector, String text) {
        if (cssSelector == null || cssSelector.isBlank()) {
            return "Error: CSS selector cannot be blank.";
        }
        if (activePage == null) {
            return "Error: Headless browser page is not initialized.";
        }
        String safeText = (text != null) ? text : "";
        try {
            activePage.locator(cssSelector.trim()).first().fill(safeText, new com.microsoft.playwright.Locator.FillOptions().setTimeout(5000));
            return "Successfully filled text into selector: " + cssSelector;
        } catch (Exception e) {
            return "Error filling input: " + e.getMessage();
        }
    }
    
    public String getCurrentHtml() {
        if (activePage == null) {
            return "";
        }
        return activePage.content();
    }

    @Override
    public void close() {
        if (activePage != null) {
            try {
                activePage.close();
            } catch (Exception ignored) {}
        }
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
