package com.agent.orchestration.services;

import com.agent.WorkerAgent;
import com.agent.factory.SubAgentFactory;
import com.agent.tools.context.services.BlackboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
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
    @DisplayName("Should retry on transient failure and succeed on second attempt")
    void shouldRetryOnTransientFailureAndSucceed() {
        AtomicInteger attempts = new AtomicInteger(0);
        WorkerAgent mockAgent = task -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("Transient network timeout");
            }
            return "Recovered on attempt 2";
        };
        when(agentFactory.getAgent("developer")).thenReturn(mockAgent);

        String result = orchestrationService.routeTask("developer", "Refactor module");

        assertThat(result).isEqualTo("Recovered on attempt 2");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should report failure after max attempts exhausted")
    void shouldReportFailureAfterMaxAttemptsExhausted() {
        AtomicInteger attempts = new AtomicInteger(0);
        WorkerAgent mockAgent = task -> {
            attempts.incrementAndGet();
            throw new RuntimeException("Permanent backend error");
        };
        when(agentFactory.getAgent("buggy")).thenReturn(mockAgent);

        String result = orchestrationService.routeTask("buggy", "execute");

        assertThat(result).contains("AGENT EXECUTION FAILED after 2 attempts. Error: Permanent backend error");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should return error message when agent is unknown")
    void shouldHandleUnknownAgent() {
        when(agentFactory.getAgent("unknown")).thenReturn(null);

        String result = orchestrationService.routeTask("unknown", "do work");
        assertThat(result).contains("AGENT EXECUTION FAILED after 2 attempts. Error: Unknown agent ID 'unknown'");
    }

    @Test
    @DisplayName("Should execute parallel tasks concurrently and synthesize responses")
    void shouldExecuteParallelTasksConcurrently() {
        WorkerAgent devopsAgent = task -> "Git status: clean";
        WorkerAgent researcherAgent = task -> "Virtual Threads allow massive concurrency.";

        when(agentFactory.getAgent("devops")).thenReturn(devopsAgent);
        when(agentFactory.getAgent("researcher")).thenReturn(researcherAgent);

        String result = orchestrationService.routeParallelTasks(
                "devops", "Check git status",
                "researcher", "Research Virtual Threads"
        );

        assertThat(result)
                .contains("=== Response from devops ===")
                .contains("Git status: clean")
                .contains("=== Response from researcher ===")
                .contains("Virtual Threads allow massive concurrency.");
    }
}
