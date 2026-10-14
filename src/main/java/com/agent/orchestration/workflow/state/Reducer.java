package com.agent.orchestration.workflow.state;

/**
 * Functional interface for state reduction in workflow channels,
 * inspired by Pregel and LangGraph.
 * Merges a partial update into the current channel state.
 *
 * @param <T> data type managed in the channel
 */
@FunctionalInterface
public interface Reducer<T> {

    /**
     * Merges an incoming partial update into the current channel state.
     * Implementations must handle null values gracefully.
     *
     * @param currentState current value in the channel (may be null if uninitialized)
     * @param update       new incoming value to merge (may be null)
     * @return the reduced/merged state
     */
    T reduce(T currentState, T update);
}
