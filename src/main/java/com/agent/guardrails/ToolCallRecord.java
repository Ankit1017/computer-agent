package com.agent.guardrails;

import java.util.Objects;

/**
 * Immutable snapshot of a tool invocation within a turn, normalized for loop detection.
 */
public record ToolCallRecord(String toolName, String normalizedArguments) {

    public ToolCallRecord {
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (normalizedArguments == null) {
            normalizedArguments = "{}";
        }
    }
}
