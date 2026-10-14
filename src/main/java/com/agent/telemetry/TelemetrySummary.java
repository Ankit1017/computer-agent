package com.agent.telemetry;

import java.util.Map;

/**
 * Summary metrics of token consumption, execution latencies, and distributed span counts across the swarm.
 */
public record TelemetrySummary(
        int totalSpans,
        int okSpans,
        int errorSpans,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double averageDurationMillis,
        Map<String, Long> spanCountsByName
) {}
