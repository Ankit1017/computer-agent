package com.agent.orchestration.workflow;

import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.model.WorkflowValidationException;
import com.agent.orchestration.workflow.services.WorkflowParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowParserServiceTest {

    private WorkflowParserService parserService;

    @BeforeEach
    void setUp() {
        parserService = new WorkflowParserService();
    }

    @Test
    @DisplayName("Should successfully parse and validate the research_pipeline.json sample resource")
    void shouldParseValidWorkflowResourceSuccessfully() {
        WorkflowGraph graph = parserService.parseFromResource("workflows/research_pipeline.json");

        assertThat(graph).isNotNull();
        assertThat(graph.workflowId()).isEqualTo("research_pipeline");
        assertThat(graph.startNodeId()).isEqualTo("node_1");
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.edges()).hasSize(3);

        // Verify nodes
        assertThat(graph.findNode("node_1")).isPresent();
        assertThat(graph.findNode("node_1").get().agentId()).isEqualTo("researcher");
        assertThat(graph.findNode("node_1").get().taskInstruction()).isEqualTo("Research Java 21 features.");

        assertThat(graph.findNode("node_2")).isPresent();
        assertThat(graph.findNode("node_2").get().agentId()).isEqualTo("architect");

        assertThat(graph.findNode("node_3")).isPresent();
        assertThat(graph.findNode("node_3").get().agentId()).isEqualTo("developer");

        // Verify edges
        List<WorkflowEdge> node1Edges = graph.getOutboundEdges("node_1");
        assertThat(node1Edges).hasSize(1);
        assertThat(node1Edges.get(0).targetNode()).isEqualTo("node_2");

        List<WorkflowEdge> node3Edges = graph.getOutboundEdges("node_3");
        assertThat(node3Edges).hasSize(1);
        assertThat(node3Edges.get(0).targetNode()).isEqualTo(WorkflowGraph.END_NODE);
    }

    @Test
    @DisplayName("Should throw validation exception when startNodeId does not exist in nodes list")
    void shouldThrowWhenStartNodeDoesNotExist() {
        String corruptedJson = """
                {
                  "workflowId": "corrupted_workflow",
                  "startNodeId": "ghost_start",
                  "nodes": [
                    { "nodeId": "node_1", "agentId": "developer", "taskInstruction": "Code" }
                  ],
                  "edges": [
                    { "sourceNode": "node_1", "targetNode": "__END__" }
                  ]
                }
                """;

        assertThatThrownBy(() -> parserService.parse(corruptedJson))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Start node 'ghost_start' does not exist");
    }

    @Test
    @DisplayName("Should throw validation exception when an edge points to a non-existent targetNode")
    void shouldThrowWhenEdgePointsToNonExistentTargetNode() {
        String corruptedJson = """
                {
                  "workflowId": "corrupted_workflow",
                  "startNodeId": "node_1",
                  "nodes": [
                    { "nodeId": "node_1", "agentId": "researcher", "taskInstruction": "Investigate" },
                    { "nodeId": "node_2", "agentId": "developer", "taskInstruction": "Build" }
                  ],
                  "edges": [
                    { "sourceNode": "node_1", "targetNode": "phantom_target" },
                    { "sourceNode": "node_2", "targetNode": "__END__" }
                  ]
                }
                """;

        assertThatThrownBy(() -> parserService.parse(corruptedJson))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Edge points to non-existent targetNode: 'phantom_target'");
    }

    @Test
    @DisplayName("Should throw validation exception when an edge references a non-existent sourceNode")
    void shouldThrowWhenEdgeReferencesNonExistentSourceNode() {
        String corruptedJson = """
                {
                  "workflowId": "corrupted_workflow",
                  "startNodeId": "node_1",
                  "nodes": [
                    { "nodeId": "node_1", "agentId": "developer", "taskInstruction": "Build" }
                  ],
                  "edges": [
                    { "sourceNode": "unknown_source", "targetNode": "__END__" }
                  ]
                }
                """;

        assertThatThrownBy(() -> parserService.parse(corruptedJson))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Edge references non-existent sourceNode: 'unknown_source'");
    }

    @Test
    @DisplayName("Should throw validation exception when orphaned nodes exist with no inbound edges")
    void shouldThrowWhenOrphanedNodeDetected() {
        String corruptedJson = """
                {
                  "workflowId": "orphaned_workflow",
                  "startNodeId": "node_1",
                  "nodes": [
                    { "nodeId": "node_1", "agentId": "researcher", "taskInstruction": "Research" },
                    { "nodeId": "node_2", "agentId": "architect", "taskInstruction": "Plan" },
                    { "nodeId": "node_orphan", "agentId": "developer", "taskInstruction": "Never reachable" }
                  ],
                  "edges": [
                    { "sourceNode": "node_1", "targetNode": "node_2" },
                    { "sourceNode": "node_2", "targetNode": "__END__" }
                  ]
                }
                """;

        assertThatThrownBy(() -> parserService.parse(corruptedJson))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Orphaned node detected: Node 'node_orphan' has no inbound edges");
    }

    @Test
    @DisplayName("Should throw validation exception when duplicate node IDs are declared")
    void shouldThrowWhenDuplicateNodeIdDeclared() {
        WorkflowGraph duplicateGraph = new WorkflowGraph(
                "duplicate_test",
                "node_1",
                List.of(
                        new WorkflowNode("node_1", "architect", "Task 1"),
                        new WorkflowNode("node_1", "developer", "Task 2")
                ),
                List.of(new WorkflowEdge("node_1", "__END__"))
        );

        assertThatThrownBy(() -> parserService.validateGraph(duplicateGraph))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Duplicate node ID detected: 'node_1'");
    }

    @Test
    @DisplayName("Should throw validation exception when JSON is malformed or invalid syntax")
    void shouldThrowWhenJsonIsMalformed() {
        String badJson = "{ invalid json structure }";

        assertThatThrownBy(() -> parserService.parse(badJson))
                .isInstanceOf(WorkflowValidationException.class)
                .hasMessageContaining("Failed to parse workflow JSON");
    }

    @Test
    @DisplayName("Should successfully parse edges with optional branching conditions")
    void shouldParseEdgesWithOptionalConditions() {
        String conditionalJson = """
                {
                  "workflowId": "conditional_flow",
                  "startNodeId": "check_status",
                  "nodes": [
                    { "nodeId": "check_status", "agentId": "architect", "taskInstruction": "Assess" },
                    { "nodeId": "fix_issues", "agentId": "developer", "taskInstruction": "Fix" },
                    { "nodeId": "deploy", "agentId": "devops", "taskInstruction": "Deploy" }
                  ],
                  "edges": [
                    { "sourceNode": "check_status", "targetNode": "fix_issues", "condition": "hasErrors == true" },
                    { "sourceNode": "check_status", "targetNode": "deploy", "condition": "hasErrors == false" },
                    { "sourceNode": "fix_issues", "targetNode": "deploy" },
                    { "sourceNode": "deploy", "targetNode": "__END__" }
                  ]
                }
                """;

        WorkflowGraph graph = parserService.parse(conditionalJson);

        assertThat(graph).isNotNull();
        List<WorkflowEdge> edges = graph.getOutboundEdges("check_status");
        assertThat(edges).hasSize(2);
        assertThat(edges.get(0).condition()).isEqualTo("hasErrors == true");
        assertThat(edges.get(1).condition()).isEqualTo("hasErrors == false");
    }
}
