package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single task node in a workflow graph assigned to a specialized sub-agent.
 *
 * @param nodeId          unique identifier for this node within the graph
 * @param agentId         identifier of the sub-agent that executes this step (e.g., "researcher", "architect")
 * @param taskInstruction prompt or task instruction to be executed by the designated agent
 */
public record WorkflowNode(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("taskInstruction") String taskInstruction
) {

    @JsonCreator
    public WorkflowNode(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("taskInstruction") String taskInstruction
    ) {
        this.nodeId = nodeId != null ? nodeId.trim() : null;
        this.agentId = agentId != null ? agentId.trim() : null;
        this.taskInstruction = taskInstruction != null ? taskInstruction.trim() : null;
    }
}
