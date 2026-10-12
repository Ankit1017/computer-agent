package com.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ContextToolsTest {

    @Test
    @DisplayName("Should successfully write to and read from the blackboard")
    void shouldWriteAndReadBlackboard() {
        ContextTools contextTools = new ContextTools();

        String writeResult = contextTools.writeToBlackboard("db_pass", "SecretPassword123!");
        assertThat(writeResult).isEqualTo("Successfully wrote key 'db_pass' to the blackboard.");

        String readResult = contextTools.readFromBlackboard("db_pass");
        assertThat(readResult).isEqualTo("SecretPassword123!");
    }

    @Test
    @DisplayName("Should return error message when key is not found on blackboard")
    void shouldReturnErrorForMissingKey() {
        ContextTools contextTools = new ContextTools();

        String readResult = contextTools.readFromBlackboard("nonexistent_key");
        assertThat(readResult).isEqualTo("Error: Key 'nonexistent_key' not found on the blackboard.");
    }

    @Test
    @DisplayName("Should validate empty or null keys on write and read")
    void shouldValidateEmptyOrNullKeys() {
        ContextTools contextTools = new ContextTools();

        assertThat(contextTools.writeToBlackboard(null, "val")).isEqualTo("Error: Key cannot be empty.");
        assertThat(contextTools.writeToBlackboard("   ", "val")).isEqualTo("Error: Key cannot be empty.");

        assertThat(contextTools.readFromBlackboard(null)).isEqualTo("Error: Key cannot be empty.");
        assertThat(contextTools.readFromBlackboard("   ")).isEqualTo("Error: Key cannot be empty.");
    }

    @Test
    @DisplayName("Should ensure thread-safe concurrent read and write operations across Virtual Threads")
    void shouldHandleConcurrentAccess() throws InterruptedException {
        ContextTools contextTools = new ContextTools();
        int taskCount = 100;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < taskCount; i++) {
                final int idx = i;
                executor.submit(() -> contextTools.writeToBlackboard("key_" + idx, "val_" + idx));
            }
        }

        assertThat(contextTools.getBlackboard()).hasSize(taskCount);
        for (int i = 0; i < taskCount; i++) {
            assertThat(contextTools.readFromBlackboard("key_" + i)).isEqualTo("val_" + i);
        }
    }
}
