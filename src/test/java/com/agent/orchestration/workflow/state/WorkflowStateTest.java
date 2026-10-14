package com.agent.orchestration.workflow.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowStateTest {

    @Test
    @DisplayName("Should correctly drop old value when applying an update to an OverwriteReducer channel")
    void shouldDropOldValueOnOverwriteReducerChannel() {
        WorkflowState state = new WorkflowState();
        state.registerReducer("status", new OverwriteReducer<>());

        state.applyUpdate(Map.of("status", "INITIALIZING"));
        assertThat(state.get("status")).isEqualTo("INITIALIZING");

        state.applyUpdate(Map.of("status", "RUNNING"));
        assertThat(state.get("status")).isEqualTo("RUNNING");

        state.applyUpdate(Map.of("status", "COMPLETED"));
        assertThat(state.get("status")).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("Should correctly merge items into a growing list on sequential updates to AppendListReducer channel")
    void shouldMergeItemsIntoGrowingListOnAppendListReducerChannel() {
        WorkflowState state = new WorkflowState();
        state.registerReducer("notes", new AppendListReducer<String>());

        // 1. Initial update
        state.applyUpdate(Map.of("notes", List.of("First research finding")));
        List<String> notesStep1 = state.get("notes", List.class);
        assertThat(notesStep1).containsExactly("First research finding");

        // 2. Second sequential update
        state.applyUpdate(Map.of("notes", List.of("Second architecture decision")));
        List<String> notesStep2 = state.get("notes", List.class);
        assertThat(notesStep2).containsExactly(
                "First research finding",
                "Second architecture decision"
        );

        // 3. Third sequential update with multiple items
        state.applyUpdate(Map.of("notes", List.of("Finding 3A", "Finding 3B")));
        List<String> notesStep3 = state.get("notes", List.class);
        assertThat(notesStep3).containsExactly(
                "First research finding",
                "Second architecture decision",
                "Finding 3A",
                "Finding 3B"
        );
    }

    @Test
    @DisplayName("Should handle null partial updates without throwing NullPointerExceptions")
    void shouldHandleNullPartialUpdatesGracefully() {
        WorkflowState state = new WorkflowState(Map.of("existingKey", "existingVal"));

        assertThatCode(() -> state.applyUpdate(null))
                .doesNotThrowAnyException();

        assertThatCode(() -> state.applyUpdate(Collections.emptyMap()))
                .doesNotThrowAnyException();

        assertThat(state.get("existingKey")).isEqualTo("existingVal");
    }

    @Test
    @DisplayName("Should use OverwriteReducer by default for unregistered channel keys")
    void shouldUseDefaultOverwriteReducerForUnregisteredKeys() {
        WorkflowState state = new WorkflowState();

        state.applyUpdate(Map.of("unregisteredChannel", "val1"));
        assertThat(state.get("unregisteredChannel")).isEqualTo("val1");

        state.applyUpdate(Map.of("unregisteredChannel", "val2"));
        assertThat(state.get("unregisteredChannel")).isEqualTo("val2");
    }

    @Test
    @DisplayName("Should retain current state when update payload contains a null value for an OverwriteReducer channel")
    void shouldRetainCurrentStateWhenUpdateValueIsNull() {
        WorkflowState state = new WorkflowState();
        state.registerReducer("config", new OverwriteReducer<>());

        state.applyUpdate(Map.of("config", "ACTIVE"));

        // Applying a null value to an existing key
        Map<String, Object> updateWithNull = Collections.singletonMap("config", null);
        state.applyUpdate(updateWithNull);

        // Should retain ACTIVE
        assertThat(state.get("config")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Should return an immutable snapshot from getData that cannot modify internal state")
    void shouldReturnImmutableSnapshotFromGetData() {
        WorkflowState state = new WorkflowState(Map.of("key1", "val1"));

        Map<String, Object> snapshot = state.getData();
        assertThat(snapshot).containsEntry("key1", "val1");

        assertThatThrownBy(() -> snapshot.put("key2", "illegalVal"))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThat(state.containsKey("key2")).isFalse();
    }

    @Test
    @DisplayName("Should safely handle concurrent updates from multiple threads without data race corruption")
    void shouldHandleConcurrentUpdatesThreadSafely() throws InterruptedException {
        WorkflowState state = new WorkflowState();
        state.registerReducer("log", new AppendListReducer<String>());

        int threadCount = 20;
        int itemsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < itemsPerThread; i++) {
                        state.applyUpdate(Map.of("log", List.of("thread-" + threadId + "-item-" + i)));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(completed).isTrue();

        List<String> combinedLogs = state.get("log", List.class);
        assertThat(combinedLogs).hasSize(threadCount * itemsPerThread);
    }
}
