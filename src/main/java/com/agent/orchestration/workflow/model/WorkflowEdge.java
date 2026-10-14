package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a directed transition from a source node to a target node in a workflow graph.
 * Supports conditional branching expressions and fallback default edge designations.
 *
 * @param sourceNode the originating node identifier
 * @param targetNode the destination node identifier (or special terminal node "__END__")
 * @param condition  optional condition expression evaluated for dynamic routing (nullable)
 * @param isDefault  whether this edge acts as the default fallback when no conditions are met
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowEdge(
        @JsonProperty("sourceNode") String sourceNode,
        @JsonProperty("targetNode") String targetNode,
        @JsonProperty("condition") String condition,
        @JsonProperty("isDefault") boolean isDefault
) {

    public WorkflowEdge {
        sourceNode = sourceNode != null ? sourceNode.trim() : null;
        targetNode = targetNode != null ? targetNode.trim() : null;
        condition = condition != null && !condition.isBlank() ? condition.trim() : null;
    }

    @JsonCreator
    public static WorkflowEdge fromJson(
            @JsonProperty("sourceNode") String sourceNode,
            @JsonProperty("targetNode") String targetNode,
            @JsonProperty("condition") String condition,
            @JsonProperty("isDefault") Boolean isDefault
    ) {
        return new WorkflowEdge(sourceNode, targetNode, condition, Boolean.TRUE.equals(isDefault));
    }

    public WorkflowEdge(String sourceNode, String targetNode) {
        this(sourceNode, targetNode, null, false);
    }

    public WorkflowEdge(String sourceNode, String targetNode, String condition) {
        this(sourceNode, targetNode, condition, false);
    }
}
