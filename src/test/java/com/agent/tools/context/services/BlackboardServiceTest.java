package com.agent.tools.context.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlackboardServiceTest {

    private final BlackboardService service = new BlackboardService();

    @Test
    @DisplayName("Should return placeholder for writeData")
    void shouldReturnPlaceholderForWriteData() {
        String result = service.writeData("global", "token", "abc-123");
        assertThat(result).contains("Placeholder: Wrote 'token' to namespace 'global'.");
    }

    @Test
    @DisplayName("Should return placeholder for readData")
    void shouldReturnPlaceholderForReadData() {
        String result = service.readData("global", "token");
        assertThat(result).contains("Placeholder: Read data for key 'token'.");
    }

    @Test
    @DisplayName("Should return placeholder for listKeys")
    void shouldReturnPlaceholderForListKeys() {
        String result = service.listKeys("global");
        assertThat(result).contains("Placeholder: Keys in namespace 'global'");
    }
}
