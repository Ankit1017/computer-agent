package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a directed transition from a source node to a target node in a workflow graph.
 * Supports an optional condition expression for future conditional branching.
 *
 * @param sourceNode the originating node identifier
 * @param targetNode the destination node identifier (or special terminal node "__END__")
 * @param condition  optional condition expression evaluated for branching (nullable)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowEdge(
        @JsonProperty("sourceNode") String sourceNode,
        @JsonProperty("targetNode") String targetNode,
        @JsonProperty("condition") String condition
) {

    @JsonCreator
    public WorkflowEdge(
            @JsonProperty("sourceNode") String sourceNode,
            @JsonProperty("targetNode") String targetNode,
            @JsonProperty("condition") String condition
    ) {
        this.sourceNode = sourceNode != null ? sourceNode.trim() : null;
        this.targetNode = targetNode != null ? targetNode.trim() : null;
        this.condition = condition != null && !condition.isBlank() ? condition.trim() : null;
    }

    public WorkflowEdge(String sourceNode, String targetNode) {
        this(sourceNode, targetNode, null);
    }
}
