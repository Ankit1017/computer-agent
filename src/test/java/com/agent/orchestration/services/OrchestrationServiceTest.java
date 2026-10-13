package com.agent.orchestration.services;

import com.agent.WorkerAgent;
import com.agent.factory.SubAgentFactory;
import com.agent.tools.context.services.BlackboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrchestrationServiceTest {

    private SubAgentFactory agentFactory;
    private BlackboardService blackboardService;
    private OrchestrationService orchestrationService;

    @BeforeEach
    void setUp() {
        agentFactory = mock(SubAgentFactory.class);
        blackboardService = new BlackboardService();
        orchestrationService = new OrchestrationService(agentFactory, blackboardService);
    }

    @AfterEach
    void tearDown() {
        blackboardService.shutdown();
    }

    @Test
    @DisplayName("Should successfully route task to agent without core context if core is empty")
    void shouldRouteTaskWithoutCoreContext() {
        AtomicReference<String> promptReceived = new AtomicReference<>();
        WorkerAgent mockAgent = task -> {
            promptReceived.set(task);
            return "Agent Response";
        };
        when(agentFactory.getAgent("developer")).thenReturn(mockAgent);

        String result = orchestrationService.routeTask("developer", "Write code");

        assertThat(result).isEqualTo("Agent Response");
        assertThat(promptReceived.get()).isEqualTo("Write code");
    }

    @Test
    @DisplayName("Should auto-inject core memory when core namespace has active entries")
    void shouldAutoInjectCoreMemoryIntoPrompt() {
        blackboardService.writeData("core", "ProjectCodeName", "Operation Phoenix");

        AtomicReference<String> promptReceived = new AtomicReference<>();
        WorkerAgent mockAgent = task -> {
            promptReceived.set(task);
            return "Task Handled";
        };
        when(agentFactory.getAgent("developer")).thenReturn(mockAgent);

        String result = orchestrationService.routeTask("developer", "What is the project code name?");

        assertThat(result).isEqualTo("Task Handled");
        assertThat(promptReceived.get())
                .contains("[[ SYSTEM CORE MEMORY (DO NOT IGNORE) ]]")
                .contains("- ProjectCodeName: Operation Phoenix")
                .contains("YOUR TASK:\nWhat is the project code name?");
    }

    @Test
    @DisplayName("Should validate empty or null inputs")
    void shouldValidateInputs() {
        assertThat(orchestrationService.routeTask(null, "task")).isEqualTo("Error: Agent ID cannot be empty.");
        assertThat(orchestrationService.routeTask("   ", "task")).isEqualTo("Error: Agent ID cannot be empty.");
        assertThat(orchestrationService.routeTask("agent", null)).isEqualTo("Error: Task description cannot be empty.");
        assertThat(orchestrationService.routeTask("agent", "   ")).isEqualTo("Error: Task description cannot be empty.");
    }

    @Test
    @DisplayName("Should return error when agent is not found")
    void shouldReturnErrorWhenAgentNotFound() {
        when(agentFactory.getAgent("unknown")).thenReturn(null);

        String result = orchestrationService.routeTask("unknown", "do work");
        assertThat(result).isEqualTo("Error: Unknown agent ID 'unknown'.");
    }

    @Test
    @DisplayName("Should catch and format exceptions from agent execution")
    void shouldHandleAgentException() {
        when(agentFactory.getAgent("buggy")).thenReturn(task -> {
            throw new RuntimeException("Model connection timeout");
        });

        String result = orchestrationService.routeTask("buggy", "execute");
        assertThat(result).isEqualTo("Error routing task to agent 'buggy': Model connection timeout");
    }

    @Test
    @DisplayName("Should return placeholder for routeParallelTasks")
    void shouldReturnPlaceholderForParallelTasks() {
        String result = orchestrationService.routeParallelTasks("agent1", "task1", "agent2", "task2");
        assertThat(result).contains("Placeholder: Parallel execution not yet implemented.");
    }
}
