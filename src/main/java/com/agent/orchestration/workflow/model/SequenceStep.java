package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

/**
 * Specification for a single step within a linear workflow sequence,
 * supporting cooperative interrupts, scoped state contracts, and Saga compensations.
 */
public record SequenceStep(
        String nodeId,
        String agentId,
        String taskInstruction,
        Boolean interruptBefore,
        Boolean interruptAfter,
        List<String> readChannels,
        List<String> writeChannels,
        String compensationAgentId,
        String compensationInstruction
) {
    @JsonCreator
    public SequenceStep(
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
        this.nodeId = (nodeId != null && !nodeId.isBlank()) ? nodeId.trim() : null;
        this.agentId = (agentId != null) ? agentId.trim() : null;
        this.taskInstruction = taskInstruction;
        this.interruptBefore = Boolean.TRUE.equals(interruptBefore);
        this.interruptAfter = Boolean.TRUE.equals(interruptAfter);
        this.readChannels = (readChannels != null) ? List.copyOf(readChannels) : Collections.emptyList();
        this.writeChannels = (writeChannels != null) ? List.copyOf(writeChannels) : Collections.emptyList();
        this.compensationInstruction = (compensationInstruction != null && !compensationInstruction.isBlank())
                ? compensationInstruction.trim()
                : null;
        this.compensationAgentId = (compensationAgentId != null && !compensationAgentId.isBlank())
                ? compensationAgentId.trim()
                : (this.compensationInstruction != null ? this.agentId : null);
    }

    public SequenceStep(
            String nodeId,
            String agentId,
            String taskInstruction,
            Boolean interruptBefore,
            Boolean interruptAfter,
            List<String> readChannels,
            List<String> writeChannels
    ) {
        this(nodeId, agentId, taskInstruction, interruptBefore, interruptAfter, readChannels, writeChannels, null, null);
    }

    public SequenceStep(String agentId, String taskInstruction) {
        this(null, agentId, taskInstruction, false, false, Collections.emptyList(), Collections.emptyList(), null, null);
    }

    public SequenceStep(String nodeId, String agentId, String taskInstruction) {
        this(nodeId, agentId, taskInstruction, false, false, Collections.emptyList(), Collections.emptyList(), null, null);
    }

    public boolean hasCompensation() {
        return compensationInstruction != null && !compensationInstruction.isBlank();
    }
}
