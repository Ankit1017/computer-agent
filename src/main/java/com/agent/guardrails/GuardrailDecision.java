package com.agent.guardrails;

/**
 * Outcome of evaluating a tool invocation against active loop and runaway execution guardrails.
 */
public record GuardrailDecision(
        boolean blocked,
        boolean warning,
        String reason,
        String blockMessage,
        String warningMessage
) {
    public boolean isBlocked() {
        return blocked;
    }

    public boolean isWarning() {
        return warning;
    }

    public static GuardrailDecision allow() {
        return new GuardrailDecision(false, false, null, null, null);
    }

    public static GuardrailDecision warn(String reason, String warningMessage) {
        return new GuardrailDecision(false, true, reason, null, warningMessage);
    }

    public static GuardrailDecision block(String reason, String blockMessage) {
        return new GuardrailDecision(true, false, reason, blockMessage, null);
    }
}
