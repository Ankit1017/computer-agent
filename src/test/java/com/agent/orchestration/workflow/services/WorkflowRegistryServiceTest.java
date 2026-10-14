package com.agent.orchestration.workflow.services;

import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.orchestration.workflow.engine.WorkflowAgentInvoker;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowExecutionResult;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.state.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRegistryServiceTest {

    private WorkflowCheckpointer checkpointer;
    private WorkflowRegistryService registryService;

    @BeforeEach
    void setUp() {
        checkpointer = new InMemoryCheckpointer();
        WorkflowAgentInvoker dummyInvoker = (agentId, prompt) -> "Output from " + agentId;
        registryService = new WorkflowRegistryService(dummyInvoker, checkpointer);
    }

    @Test
    @DisplayName("Should auto-discover research_pipeline.json on classpath upon initialization")
    void shouldDiscoverDefaultWorkflows() {
        assertThat(registryService.listWorkflowIds()).contains("research_pipeline");
        assertThat(registryService.getWorkflow("research_pipeline")).isPresent();

        WorkflowGraph graph = registryService.getWorkflow("research_pipeline").get();
        assertThat(graph.startNodeId()).isEqualTo("node_1");
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.edges()).hasSize(3);
    }

    @Test
    @DisplayName("Should register custom workflows programmatically and list them in catalog")
    void shouldRegisterWorkflowProgrammatically() {
        WorkflowNode n1 = new WorkflowNode("start", "agent1", "Step 1");
        WorkflowNode n2 = new WorkflowNode("finish", "agent2", "Step 2");
        WorkflowEdge e1 = new WorkflowEdge("start", "finish");
        WorkflowEdge e2 = new WorkflowEdge("finish", WorkflowGraph.END_NODE);

        WorkflowGraph customGraph = new WorkflowGraph(
                "custom_pipeline",
                "Custom test pipeline",
                "start",
                List.of(n1, n2),
                List.of(e1, e2)
        );

        registryService.registerWorkflow(customGraph);

        assertThat(registryService.getWorkflow("custom_pipeline")).isPresent();
        assertThat(registryService.getWorkflow("CUSTOM_PIPELINE")).isPresent(); // case-insensitive
        assertThat(registryService.listWorkflowIds()).contains("custom_pipeline");

        String catalog = registryService.getCatalogSummary();
        assertThat(catalog).contains("custom_pipeline");
        assertThat(catalog).contains("Custom test pipeline");
        assertThat(catalog).contains("agent1");
        assertThat(catalog).contains("agent2");
    }

    @Test
    @DisplayName("Should configure AppendListReducer for default and custom list channels")
    void shouldConfigureListReducersProperly() {
        WorkflowNode n1 = new WorkflowNode("step1", "agent1", "Analyze");
        WorkflowEdge e1 = new WorkflowEdge("step1", WorkflowGraph.END_NODE);
        WorkflowGraph graph = new WorkflowGraph(
                "reducer_test",
                "Testing reducers",
                "step1",
                List.of(n1),
                List.of(e1),
                List.of("custom_tags")
        );

        WorkflowState state = registryService.createInitialState(graph, Map.of(
                "notes", List.of("Initial Note"),
                "custom_tags", List.of("tag1"),
                "scalar_val", "v1"
        ));

        // Test list accumulation via state.applyUpdate
        state.applyUpdate(Map.of(
                "notes", "Second Note",
                "custom_tags", "tag2",
                "scalar_val", "v2"
        ));

        assertThat(state.get("notes")).asList().containsExactly("Initial Note", "Second Note");
        assertThat(state.get("custom_tags")).asList().containsExactly("tag1", "tag2");
        assertThat(state.get("scalar_val")).isEqualTo("v2"); // Overwritten
    }

    @Test
    @DisplayName("Should execute workflow end-to-end and return structured WorkflowExecutionResult")
    void shouldExecuteWorkflowEndToEnd() {
        WorkflowAgentInvoker mockInvoker = (agentId, prompt) -> {
            if ("researcher".equals(agentId)) {
                return "{\"findings\": \"Java 21 virtual threads\"}";
            } else if ("architect".equals(agentId)) {
                return "{\"architecture\": \"BSP model validated\"}";
            } else {
                return "Document written successfully.";
            }
        };

        registryService.setAgentInvoker(mockInvoker);

        WorkflowExecutionResult result = registryService.executeWorkflow(
                "research_pipeline",
                "thread-101",
                Map.of("topic", "Java 21")
        );

        assertThat(result.threadId()).isEqualTo("thread-101");
        assertThat(result.workflowId()).isEqualTo("research_pipeline");
        assertThat(result.status()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
        assertThat(result.isCompleted()).isTrue();
        assertThat(result.isSuspended()).isFalse();
        assertThat(result.interruptedNodes()).isEmpty();
        assertThat(result.stateData()).containsEntry("topic", "Java 21");
        assertThat(result.stateData()).containsKey("findings");
        assertThat(result.stateData()).containsKey("architecture");
    }

    @Test
    @DisplayName("Should support HITL cooperative suspension and resume cleanly")
    void shouldSuspendAndResumeWorkflowWithHITL() {
        WorkflowNode n1 = new WorkflowNode("research", "researcher", "Initial exploration");
        WorkflowNode n2 = new WorkflowNode("human_approval", "supervisor", "Approve publication", false, true); // interruptAfter
        WorkflowNode n3 = new WorkflowNode("publish", "publisher", "Deploy report");

        WorkflowEdge e1 = new WorkflowEdge("research", "human_approval");
        WorkflowEdge e2 = new WorkflowEdge("human_approval", "publish");
        WorkflowEdge e3 = new WorkflowEdge("publish", WorkflowGraph.END_NODE);

        WorkflowGraph hitlGraph = new WorkflowGraph(
                "hitl_workflow",
                "Workflow with human review gate",
                "research",
                List.of(n1, n2, n3),
                List.of(e1, e2, e3)
        );

        registryService.registerWorkflow(hitlGraph);

        WorkflowAgentInvoker mockInvoker = (agentId, prompt) -> "Completed task for " + agentId;
        registryService.setAgentInvoker(mockInvoker);

        // 1. Initial execution suspends after human_approval
        WorkflowExecutionResult initialResult = registryService.executeWorkflow(
                "hitl_workflow",
                "hitl-thread-1",
                Map.of("draft", "V1")
        );

        assertThat(initialResult.status()).isEqualTo(WorkflowExecutionStatus.SUSPENDED);
        assertThat(initialResult.isSuspended()).isTrue();
        assertThat(initialResult.interruptedNodes()).containsExactly("human_approval");
        assertThat(initialResult.stateData()).containsKey("research_output");
        assertThat(initialResult.stateData()).containsKey("human_approval_output");
        assertThat(initialResult.stateData()).doesNotContainKey("publish_output");

        // 2. Human resumes with approval update
        WorkflowExecutionResult resumeResult = registryService.resumeWorkflow(
                "hitl-thread-1",
                Map.of("approved", true)
        );

        assertThat(resumeResult.status()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
        assertThat(resumeResult.isCompleted()).isTrue();
        assertThat(resumeResult.stateData()).containsEntry("approved", true);
        assertThat(resumeResult.stateData()).containsKey("publish_output");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when executing unknown workflow or resuming unknown thread")
    void shouldThrowGracefullyOnUnknownInputs() {
        assertThatThrownBy(() -> registryService.executeWorkflow("unknown_wf", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Workflow not found with ID: 'unknown_wf'");

        assertThatThrownBy(() -> registryService.resumeWorkflow("non-existent-thread", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No checkpoint found for threadId: 'non-existent-thread'");
    }
}
