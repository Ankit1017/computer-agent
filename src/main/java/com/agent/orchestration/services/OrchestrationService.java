package com.agent.orchestration.services;

import com.agent.factory.SubAgentFactory;
import com.agent.WorkerAgent;
import com.agent.tools.context.services.BlackboardService;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class OrchestrationService implements AutoCloseable {
    private final SubAgentFactory agentFactory;
    private final BlackboardService blackboardService;
    
    // Java 21 Virtual Threads for lightweight, concurrent agent execution
    private final ExecutorService vThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public OrchestrationService(SubAgentFactory agentFactory, BlackboardService blackboardService) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        this.blackboardService = Objects.requireNonNull(blackboardService, "blackboardService must not be null");
    }

    public String routeTask(String agentId, String task) {
        System.out.println("[ORCHESTRATOR] Routing task to '" + agentId + "': " + task);
        return executeWithRetry(agentId, task, 2); // 2 max attempts for standard routes
    }

    public String routeParallelTasks(String agentId1, String task1, String agentId2, String task2) {
        System.out.println("[ORCHESTRATOR] Routing PARALLEL tasks to '" + agentId1 + "' and '" + agentId2 + "'");

        if (agentId1 == null || agentId1.isBlank() || task1 == null || task1.isBlank()) {
            return "Error: Invalid arguments for agent 1 in parallel routing.";
        }
        if (agentId2 == null || agentId2.isBlank() || task2 == null || task2.isBlank()) {
            return "Error: Invalid arguments for agent 2 in parallel routing.";
        }

        CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> 
            "=== Response from " + agentId1 + " ===\n" + executeWithRetry(agentId1, task1, 1), vThreadExecutor);
            
        CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> 
            "=== Response from " + agentId2 + " ===\n" + executeWithRetry(agentId2, task2, 1), vThreadExecutor);

        try {
            // Wait for both virtual threads to complete with a 120s timeout safeguard
            CompletableFuture.allOf(future1, future2).get(120, TimeUnit.SECONDS);
            return future1.get() + "\n\n" + future2.get();
        } catch (TimeoutException te) {
            future1.cancel(true);
            future2.cancel(true);
            return "Parallel execution timed out after 120 seconds.";
        } catch (Exception e) {
            return "Parallel execution encountered a critical error: " + e.getMessage();
        }
    }

    private String executeWithRetry(String agentId, String task, int maxAttempts) {
        if (agentId == null || agentId.isBlank()) {
            return "Error: Agent ID cannot be empty.";
        }
        if (task == null || task.isBlank()) {
            return "Error: Task description cannot be empty.";
        }

        String normalizedAgentId = agentId.trim().toLowerCase();
        int attempt = 1;

        while (attempt <= maxAttempts) {
            try {
                WorkerAgent agent = agentFactory.getAgent(normalizedAgentId);
                if (agent == null) {
                    throw new IllegalArgumentException("Unknown agent ID '" + agentId + "'");
                }
                
                String coreContext = blackboardService.dumpNamespace("core");
                String finalPrompt = task;
                
                if (coreContext != null && !coreContext.isEmpty()) {
                    finalPrompt = "[[ SYSTEM CORE MEMORY (DO NOT IGNORE) ]]\n" + 
                                  coreContext + 
                                  "\n====================================\n\n" + 
                                  "YOUR TASK:\n" + task;
                }

                String response = agent.execute(finalPrompt);
                int responseLength = (response != null) ? response.length() : 0;
                System.out.println("[main] INFO OrchestrationService - Agent '" + agentId + "' completed task. Response length: " + responseLength);
                return response != null ? response : "";
            } catch (Exception e) {
                System.err.println("[ORCHESTRATOR] Agent '" + agentId + "' encountered error on attempt " + attempt + ": " + e.getMessage());
                if (attempt == maxAttempts) {
                    return "AGENT EXECUTION FAILED after " + maxAttempts + " attempts. Error: " + e.getMessage();
                }
                attempt++;
            }
        }
        return "AGENT EXECUTION FAILED.";
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        System.out.println("[ORCHESTRATOR] Shutting down VirtualThread executor...");
        vThreadExecutor.shutdown();
        try {
            if (!vThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                vThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            vThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
