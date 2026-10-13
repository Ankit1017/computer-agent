package com.agent.orchestration.services;

import com.agent.WorkerAgent;
import com.agent.factory.SubAgentFactory;
import com.agent.tools.context.services.BlackboardService;

import java.util.Objects;

public class OrchestrationService {
    private final SubAgentFactory agentFactory;
    private final BlackboardService blackboardService;

    public OrchestrationService(SubAgentFactory agentFactory, BlackboardService blackboardService) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        this.blackboardService = Objects.requireNonNull(blackboardService, "blackboardService must not be null");
    }

    /**
     * Generic flow: Routes a task to a single sub-agent, injecting core memory.
     */
    public String routeTask(String agentId, String task) {
        System.out.println("[ORCHESTRATOR] Routing task to '" + agentId + "': " + task);
        if (agentId == null || agentId.isBlank()) {
            return "Error: Agent ID cannot be empty.";
        }
        if (task == null || task.isBlank()) {
            return "Error: Task description cannot be empty.";
        }
        try {
            WorkerAgent agent = agentFactory.getAgent(agentId);
            if (agent == null) {
                return "Error: Unknown agent ID '" + agentId + "'.";
            }

            // Auto-inject core memory (preserving Milestone 9.0.3.2 logic)
            String coreContext = blackboardService.dumpNamespace("core");
            String finalPrompt = task;

            if (!coreContext.isEmpty()) {
                finalPrompt = "[[ SYSTEM CORE MEMORY (DO NOT IGNORE) ]]\n" + 
                              coreContext + 
                              "\n====================================\n\n" + 
                              "YOUR TASK:\n" + task;
            }

            // TODO: In future milestones, wrap this in timeout/retry resilience logic.
            String response = agent.execute(finalPrompt);
            System.out.println("[main] INFO OrchestrationService - Agent '" + agentId + "' completed task. Response length: " + (response != null ? response.length() : 0));
            return response != null ? response : "";
        } catch (Exception e) {
            return "Error routing task to agent '" + agentId + "': " + e.getMessage();
        }
    }

    /**
     * Scaffold for future parallel execution.
     */
    public String routeParallelTasks(String agentId1, String task1, String agentId2, String task2) {
        // TODO: Implement Virtual Thread parallel execution in upcoming milestones.
        return "Placeholder: Parallel execution not yet implemented.";
    }
}
