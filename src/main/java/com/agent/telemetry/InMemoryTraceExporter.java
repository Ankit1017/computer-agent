package com.agent.telemetry;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe in-memory trace exporter retaining finished spans for live diagnostics, tests, and visualizer dashboards.
 */
public class InMemoryTraceExporter implements TraceExporter {

    private final List<SwarmSpan> finishedSpans = new CopyOnWriteArrayList<>();

    @Override
    public void export(SwarmSpan span) {
        if (span != null) {
            finishedSpans.add(span);
        }
    }

    public List<SwarmSpan> getFinishedSpans() {
        return Collections.unmodifiableList(finishedSpans);
    }

    public List<SwarmSpan> getSpansByName(String name) {
        if (name == null) return Collections.emptyList();
        return finishedSpans.stream()
                .filter(s -> name.equalsIgnoreCase(s.getName()))
                .toList();
    }

    public List<SwarmSpan> getSpansByTraceId(String traceId) {
        if (traceId == null) return Collections.emptyList();
        return finishedSpans.stream()
                .filter(s -> traceId.equalsIgnoreCase(s.getTraceId()))
                .toList();
    }

    public long getTotalPromptTokens() {
        return finishedSpans.stream()
                .map(s -> s.getAttribute(TelemetryAttributes.LLM_TOKENS_PROMPT))
                .filter(Number.class::isInstance)
                .mapToLong(v -> ((Number) v).longValue())
                .sum();
    }

    public long getTotalCompletionTokens() {
        return finishedSpans.stream()
                .map(s -> s.getAttribute(TelemetryAttributes.LLM_TOKENS_COMPLETION))
                .filter(Number.class::isInstance)
                .mapToLong(v -> ((Number) v).longValue())
                .sum();
    }

    public long getTotalTokens() {
        return getTotalPromptTokens() + getTotalCompletionTokens();
    }

    public int size() {
        return finishedSpans.size();
    }

    public void clear() {
        finishedSpans.clear();
    }
}
