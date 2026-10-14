package com.agent.orchestration.workflow.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Standard reducer designed for {@link List} channels.
 * Takes the current list and the incoming update and returns a new merged list.
 * Critical for preventing data loss when multiple agents yield research notes
 * or execution artifacts concurrently.
 *
 * @param <T> element type contained within the list
 */
public class AppendListReducer<T> implements Reducer<List<T>> {

    @Override
    @SuppressWarnings("unchecked")
    public List<T> reduce(List<T> currentState, List<T> update) {
        if (currentState == null && update == null) {
            return Collections.emptyList();
        }

        List<T> merged = new ArrayList<>();
        if (currentState != null) {
            merged.addAll(currentState);
        }

        if (update != null) {
            if (update instanceof Collection<?> col) {
                for (Object item : col) {
                    if (item != null) {
                        merged.add((T) item);
                    }
                }
            } else {
                merged.add((T) update);
            }
        }

        return Collections.unmodifiableList(merged);
    }
}
