package com.agent.tools;

import com.agent.WorkerAgent;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Tool used exclusively by the Swarm Orchestrator to delegate tasks to specialized sub-agents.
 */
public class DelegationTool {

    private static final Logger log = LoggerFactory.getLogger(DelegationTool.class);

    private final Map<String, WorkerAgent> swarm;

    /**
     * Initializes the delegation tool with a map of active sub-agents.
     *
     * @param swarm mapping of agent IDs to their WorkerAgent proxy instances
     */
    public DelegationTool(Map<String, WorkerAgent> swarm) {
        this.swarm = Objects.requireNonNull(swarm, "swarm must not be null");
    }

    /**
     * Delegates a specific task to a specialized sub-agent by ID.
     *
     * @param agentId unique ID of the target agent (e.g., 'web_researcher', 'code_patcher')
     * @param task    the precise instruction or prompt for the sub-agent
     * @return the result returned by the sub-agent
     */
    @Tool("Delegate a specific task to a specialized sub-agent. You MUST use this to get work done.")
    public String delegateTask(
            @P("The ID of the target agent") String agentId,
            @P("The precise instruction/task for the agent to execute") String task) {

        if (agentId == null || agentId.isBlank()) {
            return "Error: Agent ID cannot be empty.";
        }
        if (task == null || task.isBlank()) {
            return "Error: Task description cannot be empty.";
        }

        String targetId = agentId.trim();
        WorkerAgent agent = swarm.get(targetId);

        if (agent == null) {
            log.warn("Unknown agent ID requested: '{}'. Available agents: {}", targetId, swarm.keySet());
            return "Error: Unknown agent ID '" + targetId + "'. Available agents: " + swarm.keySet();
        }

        log.info("Delegating task to '{}': {}", targetId, task);
        System.out.println("\n[ORCHESTRATOR] Delegating to '" + targetId + "': " + task);
        System.out.flush();

        try {
            String result = agent.execute(task);
            log.info("Agent '{}' completed task. Response length: {}", targetId, result != null ? result.length() : 0);
            return result != null ? result : "[No response from agent]";
        } catch (Exception e) {
            log.error("Error during delegation to agent '" + targetId + "'", e);
            return "Error executing task with agent '" + targetId + "': " + e.getMessage();
        }
    }

    /**
     * Delegates multiple independent tasks to sub-agents simultaneously using Java 21 Virtual Threads.
     *
     * @param agentIds list of target agent IDs
     * @param tasks    list of exact tasks matching the order of agent IDs
     * @return combined string output of all sub-agent responses
     */
    @Tool("Delegate multiple independent tasks to sub-agents SIMULTANEOUSLY. Always use this instead of sequential delegation when tasks do not depend on each other's outputs.")
    public String delegateParallel(
            @P("List of target agent IDs") java.util.List<String> agentIds,
            @P("List of exact tasks, matching the order of agent IDs") java.util.List<String> tasks) {

        if (agentIds == null || tasks == null) {
            return "Error: agentIds and tasks lists must not be null.";
        }

        if (agentIds.size() != tasks.size()) {
            return "Error: agentIds list and tasks list must be the same size.";
        }

        StringBuilder combinedResult = new StringBuilder();
        // Use Java 21 Virtual Threads for lightweight concurrency
        try (java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<java.util.concurrent.Callable<String>> callables = new java.util.ArrayList<>();

            for (int i = 0; i < agentIds.size(); i++) {
                String agentId = agentIds.get(i);
                String task = tasks.get(i);
                WorkerAgent agent = swarm.get(agentId != null ? agentId.trim() : "");

                if (agent == null) {
                    callables.add(() -> "[Response from " + agentId + "]: Error - Unknown agent ID.");
                } else {
                    callables.add(() -> {
                        System.out.println("\n[ORCHESTRATOR-PARALLEL] Dispatching to '" + agentId + "': " + task);
                        System.out.flush();
                        return "[Response from " + agentId + "]: " + agent.execute(task);
                    });
                }
            }

            // Execute all threads concurrently and wait for completion
            java.util.List<java.util.concurrent.Future<String>> futures = executor.invokeAll(callables);
            for (java.util.concurrent.Future<String> future : futures) {
                combinedResult.append(future.get()).append("\n\n");
            }
        } catch (Exception e) {
            log.error("Error executing parallel tasks", e);
            return "Error executing parallel tasks: " + e.getMessage();
        }

        return combinedResult.toString().trim();
    }

    public Map<String, WorkerAgent> getSwarm() {
        return swarm;
    }
}
