package com.agent.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enterprise tool facade exposing OpenTelemetry diagnostic data, token usage metrics,
 * and span hierarchies to the Orchestrator and UI visualizer.
 */
public class TelemetryToolsFacade {

    private final ObjectMapper objectMapper;

    public TelemetryToolsFacade() {
        this(new ObjectMapper());
    }

    public TelemetryToolsFacade(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Tool("Get summary metrics of LLM token consumption, execution latencies, and distributed span counts across the swarm.")
    public String getTelemetrySummary() {
        TelemetrySummary summary = SwarmTelemetry.getSummary();
        return String.format(
                """
                === SWARM TELEMETRY SUMMARY ===
                Total Spans: %d (OK: %d, Error: %d)
                Prompt Tokens: %d | Completion Tokens: %d | Total Tokens: %d
                Average Span Duration: %.2f ms
                Spans by Operation: %s
                """,
                summary.totalSpans(),
                summary.okSpans(),
                summary.errorSpans(),
                summary.promptTokens(),
                summary.completionTokens(),
                summary.totalTokens(),
                summary.averageDurationMillis(),
                summary.spanCountsByName()
        ).trim();
    }

    @Tool("Get recent telemetry traces as JSON lines for diagnostics or visualizer integration.")
    public String getRecentTraces(@P("Maximum number of recent spans to return (e.g. 10)") int limit) {
        int cap = (limit > 0) ? Math.min(limit, 100) : 10;
        List<SwarmSpan> spans = SwarmTelemetry.getInMemoryExporter().getFinishedSpans();
        if (spans.isEmpty()) {
            return "No spans recorded.";
        }

        int start = Math.max(0, spans.size() - cap);
        List<SwarmSpan> subList = spans.subList(start, spans.size());

        StringBuilder sb = new StringBuilder();
        for (SwarmSpan span : subList) {
            try {
                Map<String, Object> spanMap = new LinkedHashMap<>();
                spanMap.put("name", span.getName());
                spanMap.put("traceId", span.getTraceId());
                spanMap.put("spanId", span.getSpanId());
                spanMap.put("parentSpanId", span.getParentSpanId());
                spanMap.put("durationMs", span.getDurationMillis());
                spanMap.put("status", span.getStatus().name());
                spanMap.put("attributes", span.getAttributes());
                sb.append(objectMapper.writeValueAsString(spanMap)).append("\n");
            } catch (Exception e) {
                sb.append("{\"error\":\"").append(e.getMessage()).append("\"}\n");
            }
        }
        return sb.toString().trim();
    }
}
