package com.agent.telemetry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * OpenTelemetry-aligned Span model capturing distributed execution traces, hierarchical relationships,
 * fine-grained attributes, token usage metrics, and lifecycle events.
 */
public class SwarmSpan implements AutoCloseable {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final long startTimeMillis;
    private long endTimeMillis;
    private SpanStatus status = SpanStatus.UNSET;
    private String statusMessage;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final List<SpanEvent> events = new CopyOnWriteArrayList<>();
    private final Consumer<SwarmSpan> onClose;
    private volatile boolean ended = false;

    public SwarmSpan(String traceId, String spanId, String parentSpanId, String name, long startTimeMillis, Consumer<SwarmSpan> onClose) {
        this.traceId = Objects.requireNonNull(traceId, "traceId must not be null");
        this.spanId = Objects.requireNonNull(spanId, "spanId must not be null");
        this.parentSpanId = parentSpanId;
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.startTimeMillis = startTimeMillis > 0 ? startTimeMillis : System.currentTimeMillis();
        this.onClose = onClose != null ? onClose : span -> {};
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public String getName() {
        return name;
    }

    public long getStartTimeMillis() {
        return startTimeMillis;
    }

    public long getEndTimeMillis() {
        return endTimeMillis;
    }

    public long getDurationMillis() {
        if (endTimeMillis <= 0) {
            return Math.max(0, System.currentTimeMillis() - startTimeMillis);
        }
        return Math.max(0, endTimeMillis - startTimeMillis);
    }

    public SpanStatus getStatus() {
        return status;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public List<SpanEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public boolean isEnded() {
        return ended;
    }

    public SwarmSpan setAttribute(String key, Object value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    public SwarmSpan setAllAttributes(Map<String, Object> map) {
        if (map != null) {
            map.forEach(this::setAttribute);
        }
        return this;
    }

    public SwarmSpan recordTokenUsage(int promptTokens, int completionTokens) {
        setAttribute(TelemetryAttributes.LLM_TOKENS_PROMPT, promptTokens);
        setAttribute(TelemetryAttributes.LLM_TOKENS_COMPLETION, completionTokens);
        setAttribute(TelemetryAttributes.LLM_TOKENS_TOTAL, promptTokens + completionTokens);
        return this;
    }

    public SwarmSpan setStatus(SpanStatus status, String message) {
        this.status = status;
        this.statusMessage = message;
        return this;
    }

    public SwarmSpan recordException(Throwable throwable) {
        if (throwable != null) {
            this.status = SpanStatus.ERROR;
            this.statusMessage = throwable.getMessage();
            setAttribute(TelemetryAttributes.ERROR_MESSAGE, throwable.getMessage());
            setAttribute(TelemetryAttributes.ERROR_TYPE, throwable.getClass().getName());
            addEvent("exception", Map.of(
                    "exception.type", throwable.getClass().getName(),
                    "exception.message", throwable.getMessage() != null ? throwable.getMessage() : ""
            ));
        }
        return this;
    }

    public SwarmSpan addEvent(String eventName) {
        return addEvent(eventName, Collections.emptyMap());
    }

    public SwarmSpan addEvent(String eventName, Map<String, Object> eventAttributes) {
        if (eventName != null) {
            events.add(new SpanEvent(eventName, eventAttributes));
        }
        return this;
    }

    public void end() {
        if (!ended) {
            synchronized (this) {
                if (!ended) {
                    this.endTimeMillis = System.currentTimeMillis();
                    if (this.status == SpanStatus.UNSET) {
                        this.status = SpanStatus.OK;
                    }
                    this.ended = true;
                    onClose.accept(this);
                }
            }
        }
    }

    @Override
    public void close() {
        end();
    }

    @Override
    public String toString() {
        return "SwarmSpan{" +
                "name='" + name + '\'' +
                ", traceId='" + traceId + '\'' +
                ", spanId='" + spanId + '\'' +
                ", parentSpanId='" + parentSpanId + '\'' +
                ", duration=" + getDurationMillis() + "ms" +
                ", status=" + status +
                '}';
    }
}
