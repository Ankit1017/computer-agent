package com.agent.orchestration.workflow.state;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Standard reducer designed for list channels.
 * Takes the current list (or scalar value) and the incoming update (list or scalar)
 * and returns a new merged list.
 * Critical for preventing data loss when multiple agents yield research notes
 * or execution artifacts concurrently.
 *
 * @param <T> element type contained within the list
 */
public class AppendListReducer<T> implements Reducer<Object> {

    @Override
    @SuppressWarnings("unchecked")
    public List<T> reduce(Object currentState, Object update) {
        if (currentState == null && update == null) {
            return Collections.emptyList();
        }

        List<T> merged = new ArrayList<>();
        if (currentState != null) {
            if (currentState instanceof Collection<?> col) {
                for (Object item : col) {
                    if (item != null) {
                        merged.add((T) item);
                    }
                }
            } else {
                merged.add((T) currentState);
            }
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
