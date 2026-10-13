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
    @DisplayName("Should delegate writeToBlackboard to global namespace with 0 TTL")
    void shouldDelegateWriteToBlackboard() {
        when(blackboardService.writeData("global", "key1", "val1", 0))
                .thenReturn("Wrote key 'key1' to namespace 'global' (Permanent).");

        String result = facade.writeToBlackboard("key1", "val1");

        assertThat(result).isEqualTo("Wrote key 'key1' to namespace 'global' (Permanent).");
        verify(blackboardService).writeData("global", "key1", "val1", 0);
    }

    @Test
    @DisplayName("Should delegate writeToNamespacedBlackboard to specified namespace with 0 TTL")
    void shouldDelegateWriteToNamespacedBlackboard() {
        when(blackboardService.writeData("core", "key2", "val2", 0))
                .thenReturn("Wrote key 'key2' to namespace 'core' (Permanent).");

        String result = facade.writeToNamespacedBlackboard("core", "key2", "val2");

        assertThat(result).isEqualTo("Wrote key 'key2' to namespace 'core' (Permanent).");
        verify(blackboardService).writeData("core", "key2", "val2", 0);
    }

    @Test
    @DisplayName("Should delegate writeTemporaryData to specified namespace with TTL")
    void shouldDelegateWriteTemporaryData() {
        when(blackboardService.writeData("scratchpad", "tempKey", "tempVal", 30))
                .thenReturn("Wrote key 'tempKey' to namespace 'scratchpad' (Expires in 30s).");

        String result = facade.writeTemporaryData("scratchpad", "tempKey", "tempVal", 30);

        assertThat(result).isEqualTo("Wrote key 'tempKey' to namespace 'scratchpad' (Expires in 30s).");
        verify(blackboardService).writeData("scratchpad", "tempKey", "tempVal", 30);
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
    @DisplayName("Should delegate readFromNamespacedBlackboard to specified namespace")
    void shouldDelegateReadFromNamespacedBlackboard() {
        when(blackboardService.readData("scratchpad", "key1"))
                .thenReturn("val1");

        String result = facade.readFromNamespacedBlackboard("scratchpad", "key1");

        assertThat(result).isEqualTo("val1");
        verify(blackboardService).readData("scratchpad", "key1");
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

    @Test
    @DisplayName("Should delegate listNamespacedBlackboardKeys to specified namespace")
    void shouldDelegateListNamespacedBlackboardKeys() {
        when(blackboardService.listKeys("scratchpad"))
                .thenReturn("Keys in namespace 'scratchpad': [temp1]");

        String result = facade.listNamespacedBlackboardKeys("scratchpad");

        assertThat(result).isEqualTo("Keys in namespace 'scratchpad': [temp1]");
        verify(blackboardService).listKeys("scratchpad");
    }
}
