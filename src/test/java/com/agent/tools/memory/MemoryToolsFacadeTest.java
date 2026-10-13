package com.agent.tools.memory;

import com.agent.tools.memory.services.LongTermMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryToolsFacadeTest {

    private LongTermMemoryService mockService;
    private MemoryToolsFacade facade;

    @BeforeEach
    void setUp() {
        mockService = mock(LongTermMemoryService.class);
        facade = new MemoryToolsFacade(mockService);
    }

    @Test
    @DisplayName("Should delegate storeFact to LongTermMemoryService")
    void shouldDelegateStoreFact() {
        when(mockService.storeInformation("Fact 1")).thenReturn("Stored successfully");

        String result = facade.storeFact("Fact 1");

        assertThat(result).isEqualTo("Stored successfully");
        verify(mockService, times(1)).storeInformation("Fact 1");
    }

    @Test
    @DisplayName("Should handle exceptions in storeFact gracefully")
    void shouldHandleStoreFactException() {
        when(mockService.storeInformation("Fact 1")).thenThrow(new RuntimeException("DB offline"));

        String result = facade.storeFact("Fact 1");

        assertThat(result).isEqualTo("Error storing information: DB offline");
    }

    @Test
    @DisplayName("Should delegate searchMemory to LongTermMemoryService")
    void shouldDelegateSearchMemory() {
        when(mockService.searchInformation("query 1")).thenReturn("Found matches");

        String result = facade.searchMemory("query 1");

        assertThat(result).isEqualTo("Found matches");
        verify(mockService, times(1)).searchInformation("query 1");
    }

    @Test
    @DisplayName("Should handle exceptions in searchMemory gracefully")
    void shouldHandleSearchMemoryException() {
        when(mockService.searchInformation("query 1")).thenThrow(new RuntimeException("DB offline"));

        String result = facade.searchMemory("query 1");

        assertThat(result).isEqualTo("Error searching memory: DB offline");
    }
}
