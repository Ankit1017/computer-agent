package com.agent.tools.context;

import com.agent.tools.context.services.BlackboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ContextToolsFacadeTest {

    private BlackboardService blackboardService;
    private ContextToolsFacade facade;

    @BeforeEach
    void setUp() {
        blackboardService = mock(BlackboardService.class);
        facade = new ContextToolsFacade(blackboardService);
    }

    @Test
    @DisplayName("Should delegate writeToBlackboard to global namespace")
    void shouldDelegateWriteToBlackboard() {
        when(blackboardService.writeData("global", "key1", "val1"))
                .thenReturn("Wrote 'key1' to namespace 'global'.");

        String result = facade.writeToBlackboard("key1", "val1");

        assertThat(result).isEqualTo("Wrote 'key1' to namespace 'global'.");
        verify(blackboardService).writeData("global", "key1", "val1");
    }

    @Test
    @DisplayName("Should delegate writeToNamespacedBlackboard to specified namespace")
    void shouldDelegateWriteToNamespacedBlackboard() {
        when(blackboardService.writeData("core", "key2", "val2"))
                .thenReturn("Wrote 'key2' to namespace 'core'.");

        String result = facade.writeToNamespacedBlackboard("core", "key2", "val2");

        assertThat(result).isEqualTo("Wrote 'key2' to namespace 'core'.");
        verify(blackboardService).writeData("core", "key2", "val2");
    }

    @Test
    @DisplayName("Should delegate readFromBlackboard to global namespace")
    void shouldDelegateReadFromBlackboard() {
        when(blackboardService.readData("global", "key1"))
                .thenReturn("val1");

        String result = facade.readFromBlackboard("key1");

        assertThat(result).isEqualTo("val1");
        verify(blackboardService).readData("global", "key1");
    }

    @Test
    @DisplayName("Should delegate listBlackboardKeys to global namespace")
    void shouldDelegateListBlackboardKeys() {
        when(blackboardService.listKeys("global"))
                .thenReturn("Keys in namespace 'global': [key1, key2]");

        String result = facade.listBlackboardKeys();

        assertThat(result).isEqualTo("Keys in namespace 'global': [key1, key2]");
        verify(blackboardService).listKeys("global");
    }
}
