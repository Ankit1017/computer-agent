package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a single task node in a workflow graph assigned to a specialized sub-agent,
 * supporting cooperative Human-in-the-Loop (HITL) interrupt breakpoints
 * and scoped state contracts (readChannels and writeChannels).
 *
 * @param nodeId          unique identifier for this node within the graph
 * @param agentId         identifier of the sub-agent that executes this step (e.g., "researcher", "architect")
 * @param taskInstruction prompt or task instruction to be executed by the designated agent
 * @param interruptBefore whether execution should pause immediately before invoking this node
 * @param interruptAfter  whether execution should pause immediately after this node completes
 * @param readChannels    scoped channels this node is permitted to read from state (empty = fallback to agent / unrestricted)
 * @param writeChannels   scoped channels this node is permitted to write to state (empty = fallback to agent / unrestricted)
 */
public record WorkflowNode(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("taskInstruction") String taskInstruction,
        @JsonProperty("interruptBefore") boolean interruptBefore,
        @JsonProperty("interruptAfter") boolean interruptAfter,
        @JsonProperty("readChannels") List<String> readChannels,
        @JsonProperty("writeChannels") List<String> writeChannels
) {

    public WorkflowNode {
        nodeId = nodeId != null ? nodeId.trim() : null;
        agentId = agentId != null ? agentId.trim() : null;
        taskInstruction = taskInstruction != null ? taskInstruction.trim() : null;
        readChannels = readChannels == null ? List.of() : List.copyOf(readChannels);
        writeChannels = writeChannels == null ? List.of() : List.copyOf(writeChannels);
    }

    @JsonCreator
    public static WorkflowNode fromJson(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("taskInstruction") String taskInstruction,
            @JsonProperty("interruptBefore") Boolean interruptBefore,
            @JsonProperty("interruptAfter") Boolean interruptAfter,
            @JsonProperty("readChannels") List<String> readChannels,
            @JsonProperty("writeChannels") List<String> writeChannels
    ) {
        return new WorkflowNode(
                nodeId,
                agentId,
                taskInstruction,
                Boolean.TRUE.equals(interruptBefore),
                Boolean.TRUE.equals(interruptAfter),
                readChannels,
                writeChannels
        );
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction) {
        this(nodeId, agentId, taskInstruction, false, false, List.of(), List.of());
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction, boolean interruptBefore) {
        this(nodeId, agentId, taskInstruction, interruptBefore, false, List.of(), List.of());
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction, boolean interruptBefore, boolean interruptAfter) {
        this(nodeId, agentId, taskInstruction, interruptBefore, interruptAfter, List.of(), List.of());
    }
}
