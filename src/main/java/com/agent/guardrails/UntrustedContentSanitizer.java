package com.agent.guardrails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes untrusted web content, external documents, and search snippets to neutralize
 * prompt injections, spoofed internal orchestrator headers, and raw LLM control tokens.
 */
public final class UntrustedContentSanitizer {

    // Internal orchestrator and framework headers
    private static final Pattern SPOOFED_SYSTEM_MEMORY = Pattern.compile("(?i)\\[\\[\\s*system core memory");
    private static final Pattern SPOOFED_WORKFLOW_STATE = Pattern.compile("(?i)\\[\\[\\s*active workflow state");
    private static final Pattern SPOOFED_GUARDRAIL = Pattern.compile("(?i)\\[\\s*guardrail");

    // Raw LLM control tokens (ChatML, Llama 3, etc.)
    private static final Pattern LLM_CONTROL_TOKENS = Pattern.compile("(?i)<\\|(?:im_start|im_end|eot_id|start_header_id|end_header_id|system|user|assistant)\\|>");

    // Prompt injection override phrases
    private static final Pattern INJECTION_IGNORE_PREVIOUS = Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions");
    private static final Pattern INJECTION_DISREGARD_PROMPT = Pattern.compile("(?i)disregard\\s+(your\\s+|all\\s+)?(system\\s+)?(prompt|instructions)");
    private static final Pattern INJECTION_DEV_MODE = Pattern.compile("(?i)you\\s+are\\s+now\\s+in\\s+developer\\s+mode");

    private UntrustedContentSanitizer() {}

    /**
     * Sanitizes untrusted input text, replacing dangerous prompt injections, spoofed headers,
     * and stripping raw LLM control tokens.
     *
     * @param rawText untrusted content to sanitize
     * @return SanitizationResult containing sanitized text, modified flag, and detected patterns
     */
    public static SanitizationResult sanitize(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return SanitizationResult.unchanged(rawText);
        }

        List<String> detections = new ArrayList<>();
        String current = rawText;

        // 1. Spoofed Engine Headers
        Matcher mMem = SPOOFED_SYSTEM_MEMORY.matcher(current);
        if (mMem.find()) {
            detections.add("spoofed_header:SYSTEM_CORE_MEMORY");
            current = mMem.replaceAll("[SANITIZED_SPOOFED_HEADER: SYSTEM CORE MEMORY");
        }

        Matcher mState = SPOOFED_WORKFLOW_STATE.matcher(current);
        if (mState.find()) {
            detections.add("spoofed_header:ACTIVE_WORKFLOW_STATE");
            current = mState.replaceAll("[SANITIZED_SPOOFED_HEADER: ACTIVE WORKFLOW STATE");
        }

        Matcher mGuard = SPOOFED_GUARDRAIL.matcher(current);
        if (mGuard.find()) {
            detections.add("spoofed_header:GUARDRAIL");
            current = mGuard.replaceAll("[SANITIZED_SPOOFED_HEADER: GUARDRAIL");
        }

        // 2. Raw LLM Control Tokens
        Matcher mTokens = LLM_CONTROL_TOKENS.matcher(current);
        if (mTokens.find()) {
            detections.add("raw_control_token:" + mTokens.group());
            current = mTokens.replaceAll("");
        }

        // 3. Prompt Injection Overrides
        Matcher mIgnore = INJECTION_IGNORE_PREVIOUS.matcher(current);
        if (mIgnore.find()) {
            detections.add("prompt_injection:IGNORE_INSTRUCTIONS");
            current = mIgnore.replaceAll("[SANITIZED_PROMPT_INJECTION]");
        }

        Matcher mDisregard = INJECTION_DISREGARD_PROMPT.matcher(current);
        if (mDisregard.find()) {
            detections.add("prompt_injection:DISREGARD_PROMPT");
            current = mDisregard.replaceAll("[SANITIZED_PROMPT_INJECTION]");
        }

        Matcher mDevMode = INJECTION_DEV_MODE.matcher(current);
        if (mDevMode.find()) {
            detections.add("prompt_injection:DEVELOPER_MODE");
            current = mDevMode.replaceAll("[SANITIZED_PROMPT_INJECTION]");
        }

        if (detections.isEmpty()) {
            return SanitizationResult.unchanged(rawText);
        }

        return new SanitizationResult(current, true, Collections.unmodifiableList(detections));
    }

    /**
     * Convenience helper returning the sanitized string directly.
     */
    public static String sanitizeText(String rawText) {
        return sanitize(rawText).content();
    }
}
