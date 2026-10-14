package com.agent.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Centralized OpenTelemetry distributed tracing manager for agent workflows,
 * virtual thread supersteps, and tool executions.
 */
public class SwarmTelemetry {

    private static final Logger log = LoggerFactory.getLogger(SwarmTelemetry.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ThreadLocal<Deque<SwarmSpan>> SPAN_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final List<TraceExporter> EXPORTERS = new CopyOnWriteArrayList<>();
    private static final InMemoryTraceExporter IN_MEMORY_EXPORTER = new InMemoryTraceExporter();

    static {
        EXPORTERS.add(IN_MEMORY_EXPORTER);
    }

    private SwarmTelemetry() {}

    /**
     * Starts a new span. If an active span exists on the calling thread, it is automatically
     * adopted as the parent span.
     *
     * @param name operation name (e.g. "workflow.run", "workflow.superstep", "agent.invoke")
     * @return the active SwarmSpan
     */
    public static SwarmSpan startSpan(String name) {
        Deque<SwarmSpan> stack = SPAN_STACK.get();
        SwarmSpan current = stack.peek();
        return startSpan(name, current);
    }

    /**
     * Starts a new child span explicitly linked to the specified parent span,
     * enabling distributed trace context propagation across Virtual Threads.
     *
     * @param name       operation name
     * @param parentSpan the parent span (can be null for a new root trace)
     * @return the active child SwarmSpan
     */
    public static SwarmSpan startSpan(String name, SwarmSpan parentSpan) {
        if (parentSpan != null) {
            return startSpan(name, parentSpan.getTraceId(), parentSpan.getSpanId());
        } else {
            return startSpan(name, generateHex(16), null);
        }
    }

    /**
     * Starts a span with explicit traceId and parentSpanId.
     */
    public static SwarmSpan startSpan(String name, String traceId, String parentSpanId) {
        String resolvedTraceId = (traceId != null && !traceId.isBlank()) ? traceId : generateHex(16);
        String spanId = generateHex(8);
        long startTime = System.currentTimeMillis();

        SwarmSpan span = new SwarmSpan(
                resolvedTraceId,
                spanId,
                parentSpanId,
                name,
                startTime,
                completedSpan -> {
                    Deque<SwarmSpan> stack = SPAN_STACK.get();
                    stack.remove(completedSpan);
                    if (stack.isEmpty()) {
                        SPAN_STACK.remove();
                    }
                    for (TraceExporter exporter : EXPORTERS) {
                        try {
                            exporter.export(completedSpan);
                        } catch (Exception e) {
                            log.warn("[TELEMETRY] Exporter {} failed: {}", exporter.getClass().getSimpleName(), e.getMessage());
                        }
                    }
                }
        );

        SPAN_STACK.get().push(span);
        return span;
    }

    /**
     * Returns the currently active span on the calling thread, or null if none is active.
     */
    public static SwarmSpan currentSpan() {
        return SPAN_STACK.get().peek();
    }

    /**
     * Executes a tool action within an instrumented "tool.execute" child span.
     */
    public static <T> T recordToolExecution(String toolName, String argumentsJson, Callable<T> action) throws Exception {
        try (SwarmSpan span = startSpan("tool.execute")) {
            span.setAttribute(TelemetryAttributes.TOOL_NAME, toolName);
            if (argumentsJson != null) {
                span.setAttribute(TelemetryAttributes.TOOL_ARGUMENTS_JSON, argumentsJson);
            }
            try {
                T result = action.call();
                if (result instanceof String str && (str.startsWith("Error:") || str.startsWith("ERROR") || str.startsWith("ACTION REJECTED") || str.startsWith("User denied"))) {
                    span.setStatus(SpanStatus.ERROR, str.length() > 200 ? str.substring(0, 200) + "..." : str);
                } else {
                    span.setStatus(SpanStatus.OK, "Completed");
                }
                return result;
            } catch (Exception e) {
                span.recordException(e);
                throw e;
            }
        }
    }

    /**
     * Executes a tool action with Supplier and automatic child span recording, latency tracking, and error detection.
     */
    public static <T> T executeTool(String toolName, String argumentsJson, java.util.function.Supplier<T> action) {
        try (SwarmSpan span = startSpan("tool.execute")) {
            span.setAttribute(TelemetryAttributes.TOOL_NAME, toolName);
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                span.setAttribute(TelemetryAttributes.TOOL_ARGUMENTS_JSON, argumentsJson);
            }
            try {
                T result = action.get();
                if (result instanceof String str && (str.startsWith("Error:") || str.startsWith("ERROR") || str.startsWith("ACTION REJECTED") || str.startsWith("User denied"))) {
                    span.setStatus(SpanStatus.ERROR, str.length() > 200 ? str.substring(0, 200) + "..." : str);
                } else {
                    span.setStatus(SpanStatus.OK, "Completed");
                }
                return result;
            } catch (Throwable t) {
                span.recordException(t);
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error err) throw err;
                throw new RuntimeException(t);
            }
        }
    }

    /**
     * Safely formats key-value argument pairs into a JSON string summary.
     */
    public static String formatArgs(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return "{}";
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]);
            Object val = (i + 1 < keyValues.length) ? keyValues[i + 1] : null;
            if (val instanceof String s && s.length() > 150) {
                map.put(key, s.substring(0, 147) + "...");
            } else {
                map.put(key, val);
            }
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return map.toString();
        }
    }

    /**
     * Helper to safely estimate token counts from text payload (heuristic: ~4 characters per token).
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    /**
     * Registers an additional trace exporter.
     */
    public static void addExporter(TraceExporter exporter) {
        if (exporter != null && !EXPORTERS.contains(exporter)) {
            EXPORTERS.add(exporter);
        }
    }

    /**
     * Removes a trace exporter.
     */
    public static void removeExporter(TraceExporter exporter) {
        if (exporter != null) {
            EXPORTERS.remove(exporter);
        }
    }

    /**
     * Returns the default in-memory exporter.
     */
    public static InMemoryTraceExporter getInMemoryExporter() {
        return IN_MEMORY_EXPORTER;
    }

    /**
     * Aggregates telemetry summary metrics across all finished spans.
     */
    public static TelemetrySummary getSummary() {
        List<SwarmSpan> spans = IN_MEMORY_EXPORTER.getFinishedSpans();
        int total = spans.size();
        int okCount = (int) spans.stream().filter(s -> s.getStatus() == SpanStatus.OK).count();
        int errorCount = (int) spans.stream().filter(s -> s.getStatus() == SpanStatus.ERROR).count();
        long promptTokens = IN_MEMORY_EXPORTER.getTotalPromptTokens();
        long compTokens = IN_MEMORY_EXPORTER.getTotalCompletionTokens();
        long totalTokens = IN_MEMORY_EXPORTER.getTotalTokens();

        double avgDuration = spans.stream()
                .mapToLong(SwarmSpan::getDurationMillis)
                .average()
                .orElse(0.0);

        Map<String, Long> countByName = spans.stream()
                .collect(Collectors.groupingBy(SwarmSpan::getName, Collectors.counting()));

        return new TelemetrySummary(total, okCount, errorCount, promptTokens, compTokens, totalTokens, avgDuration, countByName);
    }

    /**
     * Resets the active in-memory trace buffer and thread-local state (useful for test isolation).
     */
    public static void reset() {
        SPAN_STACK.remove();
        IN_MEMORY_EXPORTER.clear();
    }

    private static String generateHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(byteLength * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
