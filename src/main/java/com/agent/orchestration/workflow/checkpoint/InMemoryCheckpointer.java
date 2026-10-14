package com.agent.orchestration.workflow.checkpoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, in-memory implementation of {@link WorkflowCheckpointer} backed by
 * a ConcurrentHashMap. Ideal for unit tests, rapid prototyping, and ephemeral runs.
 */
public class InMemoryCheckpointer implements WorkflowCheckpointer {

    private final Map<String, List<WorkflowCheckpoint>> storage = new ConcurrentHashMap<>();

    @Override
    public void save(WorkflowCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        storage.computeIfAbsent(checkpoint.threadId(), k -> new CopyOnWriteArrayList<>())
                .add(checkpoint);
    }

    @Override
    public Optional<WorkflowCheckpoint> loadLatest(String threadId) {
        if (threadId == null) {
            return Optional.empty();
        }
        List<WorkflowCheckpoint> history = storage.get(threadId);
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        return history.stream()
                .max(Comparator.comparingInt(WorkflowCheckpoint::superstep)
                        .thenComparingLong(WorkflowCheckpoint::timestamp));
    }

    @Override
    public List<WorkflowCheckpoint> listHistory(String threadId) {
        if (threadId == null) {
            return Collections.emptyList();
        }
        List<WorkflowCheckpoint> history = storage.get(threadId);
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }
        List<WorkflowCheckpoint> sorted = new ArrayList<>(history);
        sorted.sort(Comparator.comparingInt(WorkflowCheckpoint::superstep)
                .thenComparingLong(WorkflowCheckpoint::timestamp));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * Clears all recorded checkpoints from memory.
     */
    public void clear() {
        storage.clear();
    }
}
