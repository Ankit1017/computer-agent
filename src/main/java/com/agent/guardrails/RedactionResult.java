package com.agent.guardrails;

import java.util.List;

/**
 * Outcome of redacting sensitive credentials and secrets from text or telemetry payloads.
 */
public record RedactionResult(
        String redactedText,
        boolean modified,
        int redactionCount,
        List<String> redactedTypes
) {
    public static RedactionResult unchanged(String text) {
        return new RedactionResult(text, false, 0, List.of());
    }
}
