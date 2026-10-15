package com.agent.guardrails;

import java.util.List;

/**
 * Result of content sanitization for untrusted web pages, search snippets, or external inputs.
 */
public record SanitizationResult(
        String content,
        boolean modified,
        List<String> sanitizedDetections
) {
    public static SanitizationResult unchanged(String original) {
        return new SanitizationResult(original, false, List.of());
    }
}
