package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a single task node in a workflow graph assigned to a specialized sub-agent,
 * supporting cooperative Human-in-the-Loop (HITL) interrupt breakpoints.
 *
 * @param nodeId          unique identifier for this node within the graph
 * @param agentId         identifier of the sub-agent that executes this step (e.g., "researcher", "architect")
 * @param taskInstruction prompt or task instruction to be executed by the designated agent
 * @param interruptBefore whether execution should pause immediately before invoking this node
 * @param interruptAfter  whether execution should pause immediately after this node completes
 */
public record WorkflowNode(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("taskInstruction") String taskInstruction,
        @JsonProperty("interruptBefore") boolean interruptBefore,
        @JsonProperty("interruptAfter") boolean interruptAfter
) {

    public WorkflowNode {
        nodeId = nodeId != null ? nodeId.trim() : null;
        agentId = agentId != null ? agentId.trim() : null;
        taskInstruction = taskInstruction != null ? taskInstruction.trim() : null;
    }

    @JsonCreator
    public static WorkflowNode fromJson(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("taskInstruction") String taskInstruction,
            @JsonProperty("interruptBefore") Boolean interruptBefore,
            @JsonProperty("interruptAfter") Boolean interruptAfter
    ) {
        return new WorkflowNode(
                nodeId,
                agentId,
                taskInstruction,
                Boolean.TRUE.equals(interruptBefore),
                Boolean.TRUE.equals(interruptAfter)
        );
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction) {
        this(nodeId, agentId, taskInstruction, false, false);
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction, boolean interruptBefore) {
        this(nodeId, agentId, taskInstruction, interruptBefore, false);
    }
}
