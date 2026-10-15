package com.agent.guardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe loop circuit breaker and runaway execution guard.
 * Intercepts repetitive tool-calling loops, 2-step ping-pong oscillations,
 * and runaway tool execution within single agent turns.
 */
public class ToolLoopGuard {

    public static final int DEFAULT_WARNING_THRESHOLD = 2;
    public static final int DEFAULT_BLOCK_THRESHOLD = 3;
    public static final int DEFAULT_MAX_CALLS_PER_TURN = 25;

    public static final String REASON_CONSECUTIVE = "CONSECUTIVE_IDENTICAL_CALLS";
    public static final String REASON_PING_PONG = "PING_PONG_OSCILLATION";
    public static final String REASON_MAX_CALLS = "MAX_CALLS_EXCEEDED";

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static volatile int globalWarningThreshold = DEFAULT_WARNING_THRESHOLD;
    private static volatile int globalBlockThreshold = DEFAULT_BLOCK_THRESHOLD;
    private static volatile int globalMaxCallsPerTurn = DEFAULT_MAX_CALLS_PER_TURN;

    private static final ThreadLocal<ToolLoopGuard> THREAD_GUARD =
            ThreadLocal.withInitial(() -> new ToolLoopGuard(globalWarningThreshold, globalBlockThreshold, globalMaxCallsPerTurn));

    private int warningThreshold;
    private int blockThreshold;
    private int maxCallsPerTurn;
    private final List<ToolCallRecord> turnHistory = new ArrayList<>();

    public ToolLoopGuard() {
        this(globalWarningThreshold, globalBlockThreshold, globalMaxCallsPerTurn);
    }

    public ToolLoopGuard(int warningThreshold, int blockThreshold, int maxCallsPerTurn) {
        this.warningThreshold = Math.max(1, warningThreshold);
        this.blockThreshold = Math.max(1, blockThreshold);
        this.maxCallsPerTurn = Math.max(1, maxCallsPerTurn);
    }

    // --- Instance Core Logic ---

    /**
     * Evaluates a tool call against active guardrails on this instance,
     * records it into turn history if not blocked, and returns the GuardrailDecision.
     */
    public synchronized GuardrailDecision evaluate(String toolName, String argumentsJson) {
        String normalized = normalizeArguments(argumentsJson);
        ToolCallRecord current = new ToolCallRecord(toolName, normalized);

        // 1. Max tool calls per turn cap check
        if (turnHistory.size() >= maxCallsPerTurn) {
            String msg = "[GUARDRAIL CIRCUIT BREAKER] Maximum allowed tool calls per turn ("
                    + maxCallsPerTurn + ") exceeded. Stop invoking tools and present your results to the user.";
            return GuardrailDecision.block(REASON_MAX_CALLS, msg);
        }

        // 2. Count consecutive identical calls
        int consecutiveCount = 1;
        for (int i = turnHistory.size() - 1; i >= 0; i--) {
            if (turnHistory.get(i).equals(current)) {
                consecutiveCount++;
            } else {
                break;
            }
        }

        // 3. Consecutive Block Check
        if (consecutiveCount >= blockThreshold) {
            String argsSummary = summarizeArgs(normalized);
            String msg = "[GUARDRAIL CIRCUIT BREAKER] Tool '" + toolName + "' was blocked after "
                    + consecutiveCount + " consecutive identical invocations with arguments: "
                    + argsSummary + ". Stop calling this tool with the same arguments and formulate your response using existing information.";
            return GuardrailDecision.block(REASON_CONSECUTIVE, msg);
        }

        // 4. Ping-Pong Oscillation Check (A -> B -> A -> B -> A)
        int n = turnHistory.size();
        if (n >= 4) {
            ToolCallRecord b = turnHistory.get(n - 1);
            ToolCallRecord a1 = turnHistory.get(n - 2);
            ToolCallRecord b1 = turnHistory.get(n - 3);
            ToolCallRecord a2 = turnHistory.get(n - 4);
            if (!current.equals(b) && current.equals(a1) && b.equals(b1) && current.equals(a2)) {
                String msg = "[GUARDRAIL CIRCUIT BREAKER] Ping-pong tool oscillation detected between '"
                        + current.toolName() + "' and '" + b.toolName() + "'. Break the loop and synthesize your final answer.";
                return GuardrailDecision.block(REASON_PING_PONG, msg);
            }
        }

        // Record the executed call in turn history
        turnHistory.add(current);

        // 5. Consecutive Warning Check
        if (consecutiveCount >= warningThreshold) {
            String warningMsg = "\n[GUARDRAIL WARNING: You have called '" + toolName
                    + "' with identical arguments " + consecutiveCount
                    + " times in a row. Do not repeat this identical call again; adjust your parameters or synthesize your final answer.]";
            return GuardrailDecision.warn(REASON_CONSECUTIVE, warningMsg);
        }

        return GuardrailDecision.allow();
    }

    /**
     * Inspects a prospective tool call without mutating turn history on this instance.
     */
    public synchronized GuardrailDecision inspectCall(String toolName, String argumentsJson) {
        String normalized = normalizeArguments(argumentsJson);
        ToolCallRecord current = new ToolCallRecord(toolName, normalized);

        if (turnHistory.size() >= maxCallsPerTurn) {
            String msg = "[GUARDRAIL CIRCUIT BREAKER] Maximum allowed tool calls per turn ("
                    + maxCallsPerTurn + ") exceeded. Stop invoking tools and present your results to the user.";
            return GuardrailDecision.block(REASON_MAX_CALLS, msg);
        }

        int consecutiveCount = 1;
        for (int i = turnHistory.size() - 1; i >= 0; i--) {
            if (turnHistory.get(i).equals(current)) {
                consecutiveCount++;
            } else {
                break;
            }
        }

        if (consecutiveCount >= blockThreshold) {
            String argsSummary = summarizeArgs(normalized);
            String msg = "[GUARDRAIL CIRCUIT BREAKER] Tool '" + toolName + "' was blocked after "
                    + consecutiveCount + " consecutive identical invocations with arguments: "
                    + argsSummary + ". Stop calling this tool with the same arguments and formulate your response using existing information.";
            return GuardrailDecision.block(REASON_CONSECUTIVE, msg);
        }

        int n = turnHistory.size();
        if (n >= 4) {
            ToolCallRecord b = turnHistory.get(n - 1);
            ToolCallRecord a1 = turnHistory.get(n - 2);
            ToolCallRecord b1 = turnHistory.get(n - 3);
            ToolCallRecord a2 = turnHistory.get(n - 4);
            if (!current.equals(b) && current.equals(a1) && b.equals(b1) && current.equals(a2)) {
                String msg = "[GUARDRAIL CIRCUIT BREAKER] Ping-pong tool oscillation detected between '"
                        + current.toolName() + "' and '" + b.toolName() + "'. Break the loop and synthesize your final answer.";
                return GuardrailDecision.block(REASON_PING_PONG, msg);
            }
        }

        if (consecutiveCount >= warningThreshold) {
            String warningMsg = "\n[GUARDRAIL WARNING: You have called '" + toolName
                    + "' with identical arguments " + consecutiveCount
                    + " times in a row. Do not repeat this identical call again; adjust your parameters or synthesize your final answer.]";
            return GuardrailDecision.warn(REASON_CONSECUTIVE, warningMsg);
        }

        return GuardrailDecision.allow();
    }

    /**
     * Resets the active turn history on this instance.
     */
    public synchronized void clearTurn() {
        turnHistory.clear();
    }

    public synchronized List<ToolCallRecord> getTurnHistory() {
        return Collections.unmodifiableList(new ArrayList<>(turnHistory));
    }

    public synchronized int getCallCount() {
        return turnHistory.size();
    }

    public int getWarningThreshold() {
        return warningThreshold;
    }

    public void setWarningThreshold(int warningThreshold) {
        this.warningThreshold = Math.max(1, warningThreshold);
    }

    public int getBlockThreshold() {
        return blockThreshold;
    }

    public void setBlockThreshold(int blockThreshold) {
        this.blockThreshold = Math.max(1, blockThreshold);
    }

    public int getMaxCallsPerTurn() {
        return maxCallsPerTurn;
    }

    public void setMaxCallsPerTurn(int maxCallsPerTurn) {
        this.maxCallsPerTurn = Math.max(1, maxCallsPerTurn);
    }

    // --- Static Convenience & ThreadLocal Delegation ---

    /**
     * Evaluates and records against the calling thread's ToolLoopGuard.
     */
    public static GuardrailDecision evaluateAndRecord(String toolName, String argumentsJson) {
        return THREAD_GUARD.get().evaluate(toolName, argumentsJson);
    }

    /**
     * Inspects a prospective tool call on the calling thread without mutating history.
     */
    public static GuardrailDecision inspect(String toolName, String argumentsJson) {
        return THREAD_GUARD.get().inspectCall(toolName, argumentsJson);
    }

    /**
     * Resets the turn history on the calling thread's ToolLoopGuard.
     */
    public static void resetTurn() {
        THREAD_GUARD.get().clearTurn();
    }

    /**
     * Completely removes the calling thread's ToolLoopGuard instance.
     */
    public static void reset() {
        THREAD_GUARD.remove();
    }

    public static ToolLoopGuard getInstance() {
        return THREAD_GUARD.get();
    }

    public static void setGlobalWarningThreshold(int threshold) {
        globalWarningThreshold = Math.max(1, threshold);
        THREAD_GUARD.get().setWarningThreshold(globalWarningThreshold);
    }

    public static void setGlobalBlockThreshold(int threshold) {
        globalBlockThreshold = Math.max(1, threshold);
        THREAD_GUARD.get().setBlockThreshold(globalBlockThreshold);
    }

    public static void setGlobalMaxCallsPerTurn(int limit) {
        globalMaxCallsPerTurn = Math.max(1, limit);
        THREAD_GUARD.get().setMaxCallsPerTurn(globalMaxCallsPerTurn);
    }

    public static void resetDefaults() {
        globalWarningThreshold = DEFAULT_WARNING_THRESHOLD;
        globalBlockThreshold = DEFAULT_BLOCK_THRESHOLD;
        globalMaxCallsPerTurn = DEFAULT_MAX_CALLS_PER_TURN;
        reset();
    }

    // --- Normalization & Summarization Helpers ---

    public static String normalizeArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "{}";
        }
        String trimmed = argumentsJson.trim();
        try {
            Object parsed = CANONICAL_MAPPER.readValue(trimmed, Object.class);
            return CANONICAL_MAPPER.writeValueAsString(parsed);
        } catch (Exception e) {
            return trimmed.replaceAll("\\s+", " ");
        }
    }

    private static String summarizeArgs(String args) {
        if (args == null || args.isBlank()) {
            return "{}";
        }
        if (args.length() > 100) {
            return args.substring(0, 97) + "...";
        }
        return args;
    }
}
