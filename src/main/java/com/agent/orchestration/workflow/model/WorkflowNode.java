package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Represents a single task node in a workflow graph assigned to a specialized sub-agent,
 * supporting cooperative Human-in-the-Loop (HITL) interrupt breakpoints,
 * scoped state contracts (readChannels and writeChannels),
 * and Saga pattern compensating actions (compensationAgentId and compensationInstruction).
 *
 * @param nodeId                  unique identifier for this node within the graph
 * @param agentId                 identifier of the sub-agent that executes this step (e.g., "researcher", "architect")
 * @param taskInstruction         prompt or task instruction to be executed by the designated agent
 * @param interruptBefore         whether execution should pause immediately before invoking this node
 * @param interruptAfter          whether execution should pause immediately after this node completes
 * @param readChannels            scoped channels this node is permitted to read from state (empty = unrestricted)
 * @param writeChannels           scoped channels this node is permitted to write to state (empty = unrestricted)
 * @param compensationAgentId     identifier of the sub-agent responsible for compensating this node (defaults to agentId)
 * @param compensationInstruction instruction describing how to undo or compensate this node's effects on failure
 */
public record WorkflowNode(
        @JsonProperty("nodeId") String nodeId,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("taskInstruction") String taskInstruction,
        @JsonProperty("interruptBefore") boolean interruptBefore,
        @JsonProperty("interruptAfter") boolean interruptAfter,
        @JsonProperty("readChannels") List<String> readChannels,
        @JsonProperty("writeChannels") List<String> writeChannels,
        @JsonProperty("compensationAgentId") String compensationAgentId,
        @JsonProperty("compensationInstruction") String compensationInstruction
) {

    public WorkflowNode {
        nodeId = nodeId != null ? nodeId.trim() : null;
        agentId = agentId != null ? agentId.trim() : null;
        taskInstruction = taskInstruction != null ? taskInstruction.trim() : null;
        readChannels = readChannels == null ? List.of() : List.copyOf(readChannels);
        writeChannels = writeChannels == null ? List.of() : List.copyOf(writeChannels);
        compensationInstruction = (compensationInstruction != null && !compensationInstruction.isBlank())
                ? compensationInstruction.trim()
                : null;
        compensationAgentId = (compensationAgentId != null && !compensationAgentId.isBlank())
                ? compensationAgentId.trim()
                : (compensationInstruction != null ? agentId : null);
    }

    @JsonCreator
    public static WorkflowNode fromJson(
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("taskInstruction") String taskInstruction,
            @JsonProperty("interruptBefore") Boolean interruptBefore,
            @JsonProperty("interruptAfter") Boolean interruptAfter,
            @JsonProperty("readChannels") List<String> readChannels,
            @JsonProperty("writeChannels") List<String> writeChannels,
            @JsonProperty("compensationAgentId") String compensationAgentId,
            @JsonProperty("compensationInstruction") String compensationInstruction
    ) {
        return new WorkflowNode(
                nodeId,
                agentId,
                taskInstruction,
                Boolean.TRUE.equals(interruptBefore),
                Boolean.TRUE.equals(interruptAfter),
                readChannels,
                writeChannels,
                compensationAgentId,
                compensationInstruction
        );
    }

    public WorkflowNode(
            String nodeId,
            String agentId,
            String taskInstruction,
            boolean interruptBefore,
            boolean interruptAfter,
            List<String> readChannels,
            List<String> writeChannels
    ) {
        this(nodeId, agentId, taskInstruction, interruptBefore, interruptAfter, readChannels, writeChannels, null, null);
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction) {
        this(nodeId, agentId, taskInstruction, false, false, List.of(), List.of(), null, null);
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction, boolean interruptBefore) {
        this(nodeId, agentId, taskInstruction, interruptBefore, false, List.of(), List.of(), null, null);
    }

    public WorkflowNode(String nodeId, String agentId, String taskInstruction, boolean interruptBefore, boolean interruptAfter) {
        this(nodeId, agentId, taskInstruction, interruptBefore, interruptAfter, List.of(), List.of(), null, null);
    }

    /**
     * Checks if this node defines a compensating rollback action.
     *
     * @return true if compensationInstruction is defined and non-blank
     */
    public boolean hasCompensation() {
        return compensationInstruction != null && !compensationInstruction.isBlank();
    }
}
