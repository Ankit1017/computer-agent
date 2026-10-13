package com.agent.tools.web;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.web.services.WebDistillationService;
import com.agent.tools.web.services.WebNavigationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WebToolsFacadeTest {

    private SecurityInterceptor mockSecurity;
    private WebNavigationService mockNavService;
    private WebDistillationService mockDistService;
    private WebToolsFacade facade;

    @BeforeEach
    void setUp() {
        mockSecurity = mock(SecurityInterceptor.class);
        mockNavService = mock(WebNavigationService.class);
        mockDistService = mock(WebDistillationService.class);
        facade = new WebToolsFacade(mockSecurity, mockNavService, mockDistService);
    }

    @Test
    @DisplayName("Should successfully fetch raw HTML, distill to Markdown, and return content")
    void shouldReadWebPageSuccessfully() {
        String testUrl = "https://example.com/test";
        String rawHtml = "<html><body><h1>Title</h1></body></html>";
        String distilledMarkdown = "# Title\nExtracted content.";

        when(mockNavService.fetchRawHtml(testUrl)).thenReturn(rawHtml);
        when(mockDistService.distillToMarkdown(rawHtml)).thenReturn(distilledMarkdown);

        String result = facade.readWebPage(testUrl);

        assertThat(result).isEqualTo(distilledMarkdown);
        verify(mockNavService, times(1)).fetchRawHtml(testUrl);
        verify(mockDistService, times(1)).distillToMarkdown(rawHtml);
    }

    @Test
    @DisplayName("Should catch navigation errors and return descriptive error message")
    void shouldCatchNavigationErrors() {
        String testUrl = "https://example.com/broken";
        when(mockNavService.fetchRawHtml(testUrl)).thenThrow(new RuntimeException("Connection timed out"));

        String result = facade.readWebPage(testUrl);

        assertThat(result).isEqualTo("Error reading webpage: Connection timed out");
        verify(mockNavService, times(1)).fetchRawHtml(testUrl);
        verifyNoInteractions(mockDistService);
    }

    @Test
    @DisplayName("Should catch distillation errors and return descriptive error message")
    void shouldCatchDistillationErrors() {
        String testUrl = "https://example.com/malformed";
        String rawHtml = "<html>bad</html>";
        when(mockNavService.fetchRawHtml(testUrl)).thenReturn(rawHtml);
        when(mockDistService.distillToMarkdown(rawHtml)).thenThrow(new RuntimeException("Parsing failure"));

        String result = facade.readWebPage(testUrl);

        assertThat(result).isEqualTo("Error reading webpage: Parsing failure");
        verify(mockNavService, times(1)).fetchRawHtml(testUrl);
        verify(mockDistService, times(1)).distillToMarkdown(rawHtml);
    }
}
