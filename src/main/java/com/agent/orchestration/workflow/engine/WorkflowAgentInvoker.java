package com.agent.orchestration.workflow.engine;

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
}
