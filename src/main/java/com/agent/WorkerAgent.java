package com.agent;

/**
 * Universal interface for specialized sub-agents created dynamically by SubAgentFactory.
 * Functional interface supporting both standard single-argument execution and thread-isolated
 * execution via optional memoryId.
 */
@FunctionalInterface
public interface WorkerAgent {

    /**
     * Executes an assigned task using the sub-agent's default conversation memory.
     *
     * @param task natural language instructions for the sub-agent
     * @return response produced by the sub-agent
     */
    String execute(String task);

    /**
     * Executes an assigned task using a specific, isolated memory context.
     * Prevents cross-thread context contamination during concurrent Virtual Thread executions.
     *
     * @param memoryId unique session or execution thread identifier
     * @param task     natural language instructions for the sub-agent
     * @return response produced by the sub-agent
     */
    default String execute(Object memoryId, String task) {
        return execute(task);
    }
}
