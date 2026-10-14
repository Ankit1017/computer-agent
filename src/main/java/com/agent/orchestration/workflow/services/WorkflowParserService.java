package com.agent.orchestration.workflow.services;

import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.model.WorkflowValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Service responsible for parsing declarative workflow definitions from JSON and validating
 * graph structural invariants (LangGraph-style DAG validation).
 */
public class WorkflowParserService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowParserService.class);
    private final ObjectMapper objectMapper;

    public WorkflowParserService() {
        this(new ObjectMapper());
    }

    public WorkflowParserService(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Parses a workflow definition from a JSON string and validates its structure.
     *
     * @param json JSON content representing the workflow graph
     * @return validated WorkflowGraph instance
     * @throws WorkflowValidationException if the JSON is malformed or graph validation fails
     */
    public WorkflowGraph parse(String json) {
        if (json == null || json.isBlank()) {
            throw new WorkflowValidationException("Workflow JSON string cannot be null or blank");
        }
        try {
            WorkflowGraph graph = objectMapper.readValue(json, WorkflowGraph.class);
            validateGraph(graph);
            return graph;
        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse workflow JSON: {}", e.getMessage());
            throw new WorkflowValidationException("Failed to parse workflow JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a workflow definition from an InputStream and validates its structure.
     *
     * @param inputStream stream containing workflow JSON
     * @return validated WorkflowGraph instance
     * @throws WorkflowValidationException if the stream cannot be read or validation fails
     */
    public WorkflowGraph parse(InputStream inputStream) {
        if (inputStream == null) {
            throw new WorkflowValidationException("Workflow InputStream cannot be null");
        }
        try {
            WorkflowGraph graph = objectMapper.readValue(inputStream, WorkflowGraph.class);
            validateGraph(graph);
            return graph;
        } catch (WorkflowValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse workflow InputStream: {}", e.getMessage());
            throw new WorkflowValidationException("Failed to parse workflow InputStream: " + e.getMessage(), e);
        }
    }

    /**
     * Parses a workflow definition from a file Path.
     *
     * @param path file path to the workflow JSON
     * @return validated WorkflowGraph instance
     */
    public WorkflowGraph parse(Path path) {
        if (path == null) {
            throw new WorkflowValidationException("Workflow Path cannot be null");
        }
        if (!Files.exists(path)) {
            throw new WorkflowValidationException("Workflow file does not exist: " + path);
        }
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        } catch (IOException e) {
            throw new WorkflowValidationException("Could not read workflow file: " + path, e);
        }
    }

    /**
     * Parses a workflow definition from a classpath resource.
     *
     * @param resourcePath classpath resource path (e.g., "workflows/research_pipeline.json")
     * @return validated WorkflowGraph instance
     */
    public WorkflowGraph parseFromResource(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new WorkflowValidationException("Resource path cannot be null or blank");
        }
        String normalizedPath = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        InputStream is = getClass().getClassLoader().getResourceAsStream(normalizedPath);
        if (is == null) {
            throw new WorkflowValidationException("Workflow resource not found on classpath: " + resourcePath);
        }
        try (is) {
            return parse(is);
        } catch (IOException e) {
            throw new WorkflowValidationException("Failed to read workflow resource: " + resourcePath, e);
        }
    }

    /**
     * Validates that the workflow graph satisfies structural DAG integrity:
     * 1. Start node exists in the node list.
     * 2. All edge sources exist.
     * 3. All edge targets exist or point to terminal marker '__END__'.
     * 4. No orphaned nodes exist (every non-start node must have at least one inbound edge).
     *
     * @param graph the graph to validate
     * @throws WorkflowValidationException if any invariant is violated
     */
    public void validateGraph(WorkflowGraph graph) {
        if (graph == null) {
            throw new WorkflowValidationException("WorkflowGraph cannot be null");
        }
        if (graph.workflowId() == null || graph.workflowId().isBlank()) {
            throw new WorkflowValidationException("Workflow ID cannot be null or blank");
        }
        if (graph.startNodeId() == null || graph.startNodeId().isBlank()) {
            throw new WorkflowValidationException("Start node ID cannot be null or blank");
        }
        if (graph.nodes() == null || graph.nodes().isEmpty()) {
            throw new WorkflowValidationException("Workflow must define at least one node");
        }

        // 1. Check unique and valid nodes
        Set<String> nodeIds = new HashSet<>();
        for (WorkflowNode node : graph.nodes()) {
            if (node == null) {
                throw new WorkflowValidationException("Workflow node entry cannot be null");
            }
            if (node.nodeId() == null || node.nodeId().isBlank()) {
                throw new WorkflowValidationException("Node ID cannot be null or blank");
            }
            if (!nodeIds.add(node.nodeId())) {
                throw new WorkflowValidationException("Duplicate node ID detected: '" + node.nodeId() + "'");
            }
            if (node.agentId() == null || node.agentId().isBlank()) {
                throw new WorkflowValidationException("Agent ID cannot be null or blank for node: '" + node.nodeId() + "'");
            }
        }

        // 2. Validate startNodeId exists in node list
        if (!nodeIds.contains(graph.startNodeId())) {
            throw new WorkflowValidationException(
                    "Start node '" + graph.startNodeId() + "' does not exist in the defined nodes: " + nodeIds
            );
        }

        // 3. Validate edges and record inbound targets
        Set<String> inboundTargetNodeIds = new HashSet<>();
        if (graph.edges() != null) {
            for (WorkflowEdge edge : graph.edges()) {
                if (edge == null) {
                    throw new WorkflowValidationException("Workflow edge entry cannot be null");
                }
                if (edge.sourceNode() == null || edge.sourceNode().isBlank()) {
                    throw new WorkflowValidationException("Edge sourceNode cannot be null or blank");
                }
                if (!nodeIds.contains(edge.sourceNode())) {
                    throw new WorkflowValidationException(
                            "Edge references non-existent sourceNode: '" + edge.sourceNode() + "'"
                    );
                }

                if (edge.targetNode() == null || edge.targetNode().isBlank()) {
                    throw new WorkflowValidationException("Edge targetNode cannot be null or blank");
                }

                boolean isTerminalEnd = WorkflowGraph.END_NODE.equalsIgnoreCase(edge.targetNode());
                if (!isTerminalEnd && !nodeIds.contains(edge.targetNode())) {
                    throw new WorkflowValidationException(
                            "Edge points to non-existent targetNode: '" + edge.targetNode()
                                    + "'. Target must be an existing node or '" + WorkflowGraph.END_NODE + "'"
                    );
                }

                if (!isTerminalEnd) {
                    inboundTargetNodeIds.add(edge.targetNode());
                }
            }
        }

        // 4. Validate no orphaned nodes (nodes with no inbound edges, except the start node)
        for (WorkflowNode node : graph.nodes()) {
            if (!node.nodeId().equals(graph.startNodeId())) {
                if (!inboundTargetNodeIds.contains(node.nodeId())) {
                    throw new WorkflowValidationException(
                            "Orphaned node detected: Node '" + node.nodeId() + "' has no inbound edges and is not the start node."
                    );
                }
            }
        }

        log.debug("Workflow graph '{}' successfully validated with {} nodes and {} edges.",
                graph.workflowId(), graph.nodes().size(), graph.edges().size());
    }
}
