package com.agent.telemetry;

import java.util.Collections;
import java.util.Map;

/**
 * An OpenTelemetry-aligned timestamped event attached to a Span.
 *
 * @param name            event name or annotation
 * @param timestampMillis epoch millisecond when the event occurred
 * @param attributes      arbitrary contextual key-value pairs
 */
public record SpanEvent(
        String name,
        long timestampMillis,
        Map<String, Object> attributes
) {
    public SpanEvent(String name, Map<String, Object> attributes) {
        this(name, System.currentTimeMillis(), attributes != null ? Map.copyOf(attributes) : Collections.emptyMap());
    }

    public SpanEvent(String name) {
        this(name, Collections.emptyMap());
    }
}
