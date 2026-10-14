package com.agent.orchestration.workflow.engine;

import com.agent.orchestration.workflow.state.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionEvaluatorTest {

    private ConditionEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ConditionEvaluator();
    }

    @Test
    @DisplayName("Should evaluate method calls like state.getValue('status').equals('error')")
    void shouldEvaluateMethodInvocations() {
        WorkflowState state = new WorkflowState(Map.of("status", "error"));

        assertThat(evaluator.evaluate("state.getValue(\"status\").equals(\"error\")", state)).isTrue();
        assertThat(evaluator.evaluate("state.getValue('status').equals('success')", state)).isFalse();
        assertThat(evaluator.evaluate("status.equalsIgnoreCase('ERROR')", state)).isTrue();
        assertThat(evaluator.evaluate("status.contains('rr')", state)).isTrue();
    }

    @Test
    @DisplayName("Should evaluate binary operators ==, !=, >, <, >=, <=")
    void shouldEvaluateBinaryOperators() {
        WorkflowState state = new WorkflowState(Map.of(
                "status", "APPROVED",
                "retryCount", 2,
                "score", 95.5,
                "hasErrors", false
        ));

        // String equality
        assertThat(evaluator.evaluate("status == 'APPROVED'", state)).isTrue();
        assertThat(evaluator.evaluate("status != 'REJECTED'", state)).isTrue();
        assertThat(evaluator.evaluate("status == 'PENDING'", state)).isFalse();

        // Numeric comparisons
        assertThat(evaluator.evaluate("retryCount < 3", state)).isTrue();
        assertThat(evaluator.evaluate("retryCount > 1", state)).isTrue();
        assertThat(evaluator.evaluate("score >= 90", state)).isTrue();
        assertThat(evaluator.evaluate("score <= 95.5", state)).isTrue();
        assertThat(evaluator.evaluate("retryCount == 2", state)).isTrue();

        // Boolean equality
        assertThat(evaluator.evaluate("hasErrors == false", state)).isTrue();
        assertThat(evaluator.evaluate("hasErrors == true", state)).isFalse();
    }

    @Test
    @DisplayName("Should evaluate null checks properly")
    void shouldEvaluateNullChecks() {
        WorkflowState state = new WorkflowState(Map.of("existingKey", "present"));

        assertThat(evaluator.evaluate("missingKey == null", state)).isTrue();
        assertThat(evaluator.evaluate("existingKey != null", state)).isTrue();
        assertThat(evaluator.evaluate("existingKey == null", state)).isFalse();
    }

    @Test
    @DisplayName("Should evaluate logical compound operators && and ||")
    void shouldEvaluateLogicalCombinators() {
        WorkflowState state = new WorkflowState(Map.of(
                "status", "error",
                "retryCount", 1
        ));

        assertThat(evaluator.evaluate("status == 'error' && retryCount < 3", state)).isTrue();
        assertThat(evaluator.evaluate("status == 'error' && retryCount > 5", state)).isFalse();

        assertThat(evaluator.evaluate("status == 'success' || status == 'error'", state)).isTrue();
        assertThat(evaluator.evaluate("status == 'success' || status == 'aborted'", state)).isFalse();
    }

    @Test
    @DisplayName("Should evaluate unary boolean flags")
    void shouldEvaluateUnaryFlags() {
        WorkflowState state = new WorkflowState(Map.of(
                "isFlagged", true,
                "isResolved", false
        ));

        assertThat(evaluator.evaluate("isFlagged", state)).isTrue();
        assertThat(evaluator.evaluate("!isFlagged", state)).isFalse();
        assertThat(evaluator.evaluate("!isResolved", state)).isTrue();
        assertThat(evaluator.evaluate("!nonExistent", state)).isTrue();
    }

    @Test
    @DisplayName("Should return false gracefully on null or blank condition")
    void shouldReturnFalseOnNullOrBlank() {
        WorkflowState state = new WorkflowState();

        assertThat(evaluator.evaluate(null, state)).isFalse();
        assertThat(evaluator.evaluate("", state)).isFalse();
        assertThat(evaluator.evaluate("   ", state)).isFalse();
    }
}
