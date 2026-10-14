package com.agent.orchestration.workflow.state;

/**
 * Standard reducer that replaces the current state with the incoming update.
 * If the update is null, the existing state is preserved.
 *
 * @param <T> data type managed by this reducer
 */
public class OverwriteReducer<T> implements Reducer<T> {

    @Override
    public T reduce(T currentState, T update) {
        if (update == null) {
            return currentState;
        }
        return update;
    }
}
