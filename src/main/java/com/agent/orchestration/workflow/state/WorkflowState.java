package com.agent.orchestration.workflow.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe state container for declarative workflow execution, inspired by
 * Pregel / Bulk Synchronous Parallel (BSP) and LangGraph.
 *
 * <p>Holds the shared payload in designated channels and coordinates state mutations
 * through explicit {@link Reducer} functions, preventing race conditions and data loss
 * during parallel agent execution.
 */
public class WorkflowState {

    private static final Logger log = LoggerFactory.getLogger(WorkflowState.class);

    private final Map<String, Object> data;
    private final Map<String, Reducer<?>> channelReducers;
    private final Reducer<Object> defaultReducer;

    /**
     * Initializes an empty WorkflowState with a standard {@link OverwriteReducer} default fallback.
     */
    public WorkflowState() {
        this(Collections.emptyMap(), Collections.emptyMap(), new OverwriteReducer<>());
    }

    /**
     * Initializes a WorkflowState with initial data and standard fallback reducer.
     *
     * @param initialData seed data to populate
     */
    public WorkflowState(Map<String, Object> initialData) {
        this(initialData, Collections.emptyMap(), new OverwriteReducer<>());
    }

    /**
     * Initializes a WorkflowState with initial data and channel-specific reducers.
     *
     * @param initialData     seed data to populate
     * @param channelReducers channel-to-reducer registry
     */
    public WorkflowState(Map<String, Object> initialData, Map<String, Reducer<?>> channelReducers) {
        this(initialData, channelReducers, new OverwriteReducer<>());
    }

    /**
     * Full constructor for complete initialization.
     *
     * @param initialData     seed data to populate
     * @param channelReducers channel-to-reducer registry
     * @param defaultReducer  fallback reducer for unregistered channels
     */
    public WorkflowState(
            Map<String, Object> initialData,
            Map<String, Reducer<?>> channelReducers,
            Reducer<Object> defaultReducer
    ) {
        this.data = new ConcurrentHashMap<>();
        this.channelReducers = new ConcurrentHashMap<>();
        this.defaultReducer = Objects.requireNonNull(defaultReducer, "defaultReducer must not be null");

        if (channelReducers != null) {
            this.channelReducers.putAll(channelReducers);
        }

        if (initialData != null && !initialData.isEmpty()) {
            applyUpdate(initialData);
        }
    }

    /**
     * Registers a dedicated {@link Reducer} for a specific channel key.
     *
     * @param channel channel name (e.g., "notes", "messages", "artifacts")
     * @param reducer reducer instance responsible for merging updates to this channel
     */
    public void registerReducer(String channel, Reducer<?> reducer) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("Channel name cannot be null or blank");
        }
        channelReducers.put(channel.trim(), Objects.requireNonNull(reducer, "Reducer must not be null"));
    }

    /**
     * Atomically merges a partial update payload into the active workflow state.
     * Each entry is reduced using its registered channel reducer, or the default fallback reducer.
     *
     * @param partialUpdate key-value pairs representing the partial state update to merge
     */
    @SuppressWarnings("unchecked")
    public synchronized void applyUpdate(Map<String, Object> partialUpdate) {
        if (partialUpdate == null || partialUpdate.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : partialUpdate.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }

            key = key.trim();
            Object incomingValue = entry.getValue();
            Object currentValue = data.get(key);

            Reducer<Object> reducer = (Reducer<Object>) channelReducers.getOrDefault(key, defaultReducer);
            try {
                Object reducedValue = reducer.reduce(currentValue, incomingValue);
                if (reducedValue != null) {
                    data.put(key, reducedValue);
                } else {
                    data.remove(key);
                }
            } catch (Exception e) {
                log.error("Failed to reduce channel '{}' with incoming value '{}': {}", key, incomingValue, e.getMessage());
                throw new IllegalStateException("Reduction failure on channel '" + key + "': " + e.getMessage(), e);
            }
        }
    }

    /**
     * Restores internal state from a checkpoint snapshot. Existing channels are cleared
     * and replaced with the snapshot payload without triggering reducers.
     *
     * @param snapshot the serialized state map to restore
     */
    public synchronized void restoreState(Map<String, Object> snapshot) {
        this.data.clear();
        if (snapshot != null && !snapshot.isEmpty()) {
            this.data.putAll(snapshot);
        }
    }

    /**
     * Retrieves an immutable, detached snapshot of the current state payload.
     * Modifications to the returned map are disallowed and do not impact internal state.
     *
     * @return unmodifiable copy of current data
     */
    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(new HashMap<>(data));
    }

    /**
     * Retrieves a channel's current value.
     *
     * @param key channel key
     * @return current value, or null if uninitialized
     */
    public Object get(String key) {
        if (key == null) return null;
        return data.get(key.trim());
    }

    /**
     * Retrieves a channel's current value cast to the requested type.
     *
     * @param key  channel key
     * @param type expected target class
     * @param <T>  target type
     * @return casted value, or null if uninitialized
     */
    public <T> T get(String key, Class<T> type) {
        Object val = get(key);
        if (val == null) {
            return null;
        }
        return type.cast(val);
    }

    /**
     * Checks if a channel currently has an initialized value.
     *
     * @param key channel key
     * @return true if channel exists in data
     */
    public boolean containsKey(String key) {
        if (key == null) return false;
        return data.containsKey(key.trim());
    }

    /**
     * Returns the reducer assigned to a specific channel, or the default fallback reducer.
     *
     * @param channel channel key
     * @return assigned or fallback Reducer instance
     */
    public Reducer<?> getReducer(String channel) {
        if (channel == null) return defaultReducer;
        return channelReducers.getOrDefault(channel.trim(), defaultReducer);
    }
}
