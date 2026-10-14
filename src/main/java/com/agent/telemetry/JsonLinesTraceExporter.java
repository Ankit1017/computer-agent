package com.agent.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * File-based JSON Lines exporter writing completed spans sequentially to disk
 * (defaulting to .traces/swarm_traces.jsonl).
 */
public class JsonLinesTraceExporter implements TraceExporter {

    private static final Logger log = LoggerFactory.getLogger(JsonLinesTraceExporter.class);

    private final Path logFilePath;
    private final ObjectMapper objectMapper;

    public JsonLinesTraceExporter() {
        this(Paths.get(".traces", "swarm_traces.jsonl"), new ObjectMapper());
    }

    public JsonLinesTraceExporter(Path logFilePath) {
        this(logFilePath, new ObjectMapper());
    }

    public JsonLinesTraceExporter(Path logFilePath, ObjectMapper objectMapper) {
        this.logFilePath = logFilePath != null ? logFilePath : Paths.get(".traces", "swarm_traces.jsonl");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public Path getLogFilePath() {
        return logFilePath;
    }

    @Override
    public synchronized void export(SwarmSpan span) {
        if (span == null) {
            return;
        }

        try {
            Path parent = logFilePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Map<String, Object> spanMap = new LinkedHashMap<>();
            spanMap.put("traceId", span.getTraceId());
            spanMap.put("spanId", span.getSpanId());
            spanMap.put("parentSpanId", span.getParentSpanId());
            spanMap.put("name", span.getName());
            spanMap.put("status", span.getStatus().name());
            spanMap.put("statusMessage", span.getStatusMessage());
            spanMap.put("startTimeMillis", span.getStartTimeMillis());
            spanMap.put("endTimeMillis", span.getEndTimeMillis());
            spanMap.put("durationMillis", span.getDurationMillis());
            spanMap.put("attributes", span.getAttributes());
            spanMap.put("events", span.getEvents());

            String jsonLine = objectMapper.writeValueAsString(spanMap) + "\n";
            Files.writeString(
                    logFilePath,
                    jsonLine,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            log.warn("[TELEMETRY] Failed to write span to JSONL file '{}': {}", logFilePath, e.getMessage());
        }
    }
}
