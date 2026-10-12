package com.agent.tools;

import com.agent.WorkerAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationToolTest {

    @Test
    @DisplayName("Should successfully delegate task to the specified agent")
    void shouldDelegateTaskToTargetAgent() {
        Map<String, WorkerAgent> swarm = new HashMap<>();
        swarm.put("researcher", task -> "Research results for: " + task);
        swarm.put("patcher", task -> "Patcher applied: " + task);

        DelegationTool tool = new DelegationTool(swarm);

        String result = tool.delegateTask("researcher", "find docs");
        assertThat(result).isEqualTo("Research results for: find docs");

        String patchResult = tool.delegateTask("patcher", "fix file");
        assertThat(patchResult).isEqualTo("Patcher applied: fix file");
    }

    @Test
    @DisplayName("Should return descriptive error when agentId is unknown")
    void shouldReturnErrorForUnknownAgent() {
        Map<String, WorkerAgent> swarm = Map.of("worker_a", task -> "done");
        DelegationTool tool = new DelegationTool(swarm);

        String result = tool.delegateTask("unknown_worker", "do work");
        assertThat(result)
                .startsWith("Error: Unknown agent ID 'unknown_worker'")
                .contains("Available agents: [worker_a]");
    }

    @Test
    @DisplayName("Should validate empty or null inputs")
    void shouldValidateInputs() {
        DelegationTool tool = new DelegationTool(Map.of());

        assertThat(tool.delegateTask(null, "task")).isEqualTo("Error: Agent ID cannot be empty.");
        assertThat(tool.delegateTask("   ", "task")).isEqualTo("Error: Agent ID cannot be empty.");
        assertThat(tool.delegateTask("agent", null)).isEqualTo("Error: Task description cannot be empty.");
        assertThat(tool.delegateTask("agent", "   ")).isEqualTo("Error: Task description cannot be empty.");
    }

    @Test
    @DisplayName("Should catch and format exceptions thrown by worker agent")
    void shouldHandleWorkerException() {
        Map<String, WorkerAgent> swarm = Map.of("faulty_worker", task -> {
            throw new RuntimeException("Worker failed connection");
        });
        DelegationTool tool = new DelegationTool(swarm);

        String result = tool.delegateTask("faulty_worker", "run something");
        assertThat(result).isEqualTo("Error executing task with agent 'faulty_worker': Worker failed connection");
    }

    @Test
    @DisplayName("Should throw NullPointerException if swarm map is null in constructor")
    void shouldThrowIfSwarmIsNull() {
        assertThatThrownBy(() -> new DelegationTool(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("swarm must not be null");
    }

    @Test
    @DisplayName("Should successfully delegate tasks in parallel using Virtual Threads")
    void shouldDelegateTasksInParallelWithVirtualThreads() {
        Map<String, WorkerAgent> swarm = new HashMap<>();
        swarm.put("researcher", task -> "Research result for: " + task);
        swarm.put("patcher", task -> "Patch applied: " + task);

        DelegationTool tool = new DelegationTool(swarm);

        String combined = tool.delegateParallel(
                java.util.List.of("researcher", "patcher"),
                java.util.List.of("lookup java 21", "write line 3")
        );

        assertThat(combined)
                .contains("[Response from researcher]: Research result for: lookup java 21")
                .contains("[Response from patcher]: Patch applied: write line 3");
    }

    @Test
    @DisplayName("Should return error when agentIds and tasks list sizes mismatch")
    void shouldReturnErrorWhenParallelListsSizeMismatch() {
        DelegationTool tool = new DelegationTool(Map.of());

        String result = tool.delegateParallel(
                java.util.List.of("agent1", "agent2"),
                java.util.List.of("task1")
        );

        assertThat(result).isEqualTo("Error: agentIds list and tasks list must be the same size.");
    }

    @Test
    @DisplayName("Should validate null lists in parallel delegation")
    void shouldValidateNullListsInParallelDelegation() {
        DelegationTool tool = new DelegationTool(Map.of());

        assertThat(tool.delegateParallel(null, java.util.List.of())).isEqualTo("Error: agentIds and tasks lists must not be null.");
        assertThat(tool.delegateParallel(java.util.List.of(), null)).isEqualTo("Error: agentIds and tasks lists must not be null.");
    }

    @Test
    @DisplayName("Should handle unknown agent ID in parallel delegation gracefully")
    void shouldHandleUnknownAgentInParallelDelegation() {
        Map<String, WorkerAgent> swarm = Map.of("known_agent", task -> "OK: " + task);
        DelegationTool tool = new DelegationTool(swarm);

        String result = tool.delegateParallel(
                java.util.List.of("known_agent", "ghost_agent"),
                java.util.List.of("task 1", "task 2")
        );

        assertThat(result)
                .contains("[Response from known_agent]: OK: task 1")
                .contains("[Response from ghost_agent]: Error - Unknown agent ID.");
    }
}
