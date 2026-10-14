package com.agent.orchestration.workflow.engine;

import com.agent.config.AgentConfig;

/**
 * Functional interface for dispatching tasks to agents during workflow execution.
 * Allows decoupling the execution engine from concrete agent implementations
 * and simplifies unit testing with mocks or stubs.
 */
@FunctionalInterface
public interface WorkflowAgentInvoker {

    /**
     * Dispatches a prompt to the specified agent and awaits the response.
     *
     * @param agentId identifier of the sub-agent (e.g., "researcher", "architect")
     * @param prompt  fully formatted prompt including active workflow state context
     * @return textual response from the agent
     */
    String invoke(String agentId, String prompt);

    /**
     * Dispatches a prompt to the specified agent with an isolated execution memory context.
     * Prevents cross-thread context contamination during concurrent Virtual Thread executions.
     *
     * @param agentId  identifier of the sub-agent (e.g., "researcher", "architect")
     * @param prompt   fully formatted prompt including active workflow state context
     * @param memoryId unique session or execution thread identifier
     * @return textual response from the agent
     */
    default String invoke(String agentId, String prompt, Object memoryId) {
        return invoke(agentId, prompt);
    }

    /**
     * Retrieves optional configuration and contract metadata for the specified agent.
     *
     * @param agentId identifier of the sub-agent
     * @return AgentConfig if registered, or null
     */
    default AgentConfig getConfig(String agentId) {
        return null;
    }
}
