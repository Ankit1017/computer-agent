package com.agent.orchestration.workflow.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Represents a declarative Directed Acyclic Graph (DAG) workflow definition.
 * Consists of an initial starting node, a collection of task nodes, and directed edges.
 *
 * @param workflowId  unique identifier for the workflow
 * @param startNodeId entry node identifier where execution commences
 * @param nodes       list of task nodes defined in this workflow
 * @param edges        list of directed transitions between nodes
 * @param listChannels optional channel names that should accumulate list state across steps
 */
public record WorkflowGraph(
        @JsonProperty("workflowId") String workflowId,
        @JsonProperty("description") String description,
        @JsonProperty("startNodeId") String startNodeId,
        @JsonProperty("nodes") List<WorkflowNode> nodes,
        @JsonProperty("edges") List<WorkflowEdge> edges,
        @JsonProperty("listChannels") List<String> listChannels
) {

    public static final String END_NODE = "__END__";

    @JsonCreator
    public WorkflowGraph(
            @JsonProperty("workflowId") String workflowId,
            @JsonProperty("description") String description,
            @JsonProperty("startNodeId") String startNodeId,
            @JsonProperty("nodes") List<WorkflowNode> nodes,
            @JsonProperty("edges") List<WorkflowEdge> edges,
            @JsonProperty("listChannels") List<String> listChannels
    ) {
        this.workflowId = workflowId != null ? workflowId.trim() : null;
        this.description = description != null && !description.isBlank() ? description.trim() : null;
        this.startNodeId = startNodeId != null ? startNodeId.trim() : null;
        this.nodes = nodes != null ? Collections.unmodifiableList(nodes) : Collections.emptyList();
        this.edges = edges != null ? Collections.unmodifiableList(edges) : Collections.emptyList();
        this.listChannels = listChannels != null ? Collections.unmodifiableList(listChannels) : Collections.emptyList();
    }

    public WorkflowGraph(
            String workflowId,
            String startNodeId,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges
    ) {
        this(workflowId, null, startNodeId, nodes, edges, Collections.emptyList());
    }

    public WorkflowGraph(
            String workflowId,
            String startNodeId,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges,
            List<String> listChannels
    ) {
        this(workflowId, null, startNodeId, nodes, edges, listChannels);
    }

    public WorkflowGraph(
            String workflowId,
            String description,
            String startNodeId,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges
    ) {
        this(workflowId, description, startNodeId, nodes, edges, Collections.emptyList());
    }

    /**
     * Finds a node by its unique nodeId.
     *
     * @param nodeId node identifier to look up
     * @return Optional containing the WorkflowNode if found, or empty Optional
     */
    public Optional<WorkflowNode> findNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return Optional.empty();
        }
        return nodes.stream()
                .filter(n -> nodeId.trim().equalsIgnoreCase(n.nodeId()))
                .findFirst();
    }

    /**
     * Returns all outbound edges originating from the specified source node.
     *
     * @param sourceNodeId originating node identifier
     * @return list of outbound WorkflowEdge instances
     */
    public List<WorkflowEdge> getOutboundEdges(String sourceNodeId) {
        if (sourceNodeId == null || sourceNodeId.isBlank()) {
            return Collections.emptyList();
        }
        return edges.stream()
                .filter(e -> sourceNodeId.trim().equalsIgnoreCase(e.sourceNode()))
                .toList();
    }

    /**
     * Returns a lookup map of nodeId to WorkflowNode.
     *
     * @return map of node identifiers to WorkflowNode instances
     */
    public Map<String, WorkflowNode> nodeMap() {
        return nodes.stream()
                .collect(Collectors.toMap(
                        WorkflowNode::nodeId,
                        Function.identity(),
                        (existing, replacement) -> existing
                ));
    }
}
