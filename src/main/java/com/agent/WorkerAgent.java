package com.agent;

/**
 * Universal interface for specialized sub-agents created dynamically by SubAgentFactory.
 */
public interface WorkerAgent {

    /**
     * Executes an assigned task using the sub-agent's specialized prompt and tools.
     *
     * @param task natural language instructions for the sub-agent
     * @return response produced by the sub-agent
     */
    String execute(String task);
}
