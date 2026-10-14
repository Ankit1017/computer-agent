package com.agent.orchestration.workflow;

import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpointer;
import com.agent.orchestration.workflow.engine.WorkflowAgentInvoker;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowToolsFacadeTest {

    private WorkflowCheckpointer checkpointer;
    private WorkflowRegistryService registryService;
    private WorkflowToolsFacade facade;

    @BeforeEach
    void setUp() {
        checkpointer = new InMemoryCheckpointer();
        WorkflowAgentInvoker mockInvoker = (agentId, prompt) -> {
            if ("researcher".equals(agentId)) {
                return "{\"summary\": \"Research complete for " + agentId + "\"}";
            } else if ("architect".equals(agentId)) {
                return "{\"spec\": \"Architecture design finalized\"}";
            } else if ("developer".equals(agentId)) {
                return "Implementation verified and saved to disk.";
            } else {
                return "Step processed by " + agentId;
            }
        };

        registryService = new WorkflowRegistryService(mockInvoker, checkpointer);
        facade = new WorkflowToolsFacade(registryService);
    }

    @Test
    @DisplayName("Should list available workflows in formatted catalog via listAvailableWorkflows()")
    void shouldListAvailableWorkflowsInCatalog() {
        String catalog = facade.listAvailableWorkflows();

        assertThat(catalog).isNotBlank();
        assertThat(catalog).contains("=== REGISTERED WORKFLOW CATALOG ===");
        assertThat(catalog).contains("research_pipeline");
        assertThat(catalog).contains("Start Node: node_1");
        assertThat(catalog).contains("Agent: researcher");
        assertThat(catalog).contains("Agent: architect");
        assertThat(catalog).contains("Agent: developer");
        assertThat(catalog).contains("node_1 -> node_2");
        assertThat(catalog).contains("node_2 -> node_3");
        assertThat(catalog).contains("node_3 -> __END__");
    }

    @Test
    @DisplayName("Should execute workflow end-to-end and format markdown execution report")
    void shouldExecuteWorkflowEndToEndViaFacade() {
        String report = facade.executeWorkflow("research_pipeline", "{\"topic\": \"Java 21\"}");

        assertThat(report).contains("=== WORKFLOW EXECUTION REPORT ===");
        assertThat(report).contains("Workflow ID: research_pipeline");
        assertThat(report).contains("Status: COMPLETED");
        assertThat(report).contains("Thread ID:");
        assertThat(report).contains("State Snapshot:");
        assertThat(report).contains("\"topic\" : \"Java 21\"");
        assertThat(report).contains("\"summary\" : \"Research complete for researcher\"");
        assertThat(report).contains("\"spec\" : \"Architecture design finalized\"");
    }

    @Test
    @DisplayName("Should handle HITL suspension and resumption cleanly through the facade")
    void shouldHandleHITLSuspendAndResumeViaFacade() {
        // Register a workflow with an interrupt breakpoint
        WorkflowNode n1 = new WorkflowNode("prepare", "developer", "Prepare release notes");
        WorkflowNode n2 = new WorkflowNode("human_gate", "supervisor", "Review and authorize deployment", false, true); // interruptAfter
        WorkflowNode n3 = new WorkflowNode("deploy", "developer", "Deploy to production");

        WorkflowEdge e1 = new WorkflowEdge("prepare", "human_gate");
        WorkflowEdge e2 = new WorkflowEdge("human_gate", "deploy");
        WorkflowEdge e3 = new WorkflowEdge("deploy", WorkflowGraph.END_NODE);

        WorkflowGraph deploymentGraph = new WorkflowGraph(
                "deploy_pipeline",
                "Production release pipeline with human gate",
                "prepare",
                List.of(n1, n2, n3),
                List.of(e1, e2, e3)
        );

        registryService.registerWorkflow(deploymentGraph);

        // 1. Initial execution suspends at human_gate
        String initialReport = facade.executeWorkflow("deploy_pipeline", "{\"version\": \"2.0.0\"}");

        assertThat(initialReport).contains("=== WORKFLOW EXECUTION REPORT ===");
        assertThat(initialReport).contains("Status: SUSPENDED");
        assertThat(initialReport).contains("Interrupted Nodes: [human_gate]");
        assertThat(initialReport).contains("prepare_output");
        assertThat(initialReport).doesNotContain("deploy_output");

        // Extract auto-generated threadId from report
        Pattern pattern = Pattern.compile("Thread ID:\\s*([a-fA-F0-9\\-]+)");
        Matcher matcher = pattern.matcher(initialReport);
        assertThat(matcher.find()).isTrue();
        String threadId = matcher.group(1).trim();
        assertThat(threadId).isNotBlank();

        // 2. Human resumes execution with approval state injection
        String resumeReport = facade.resumeWorkflow(threadId, "{\"approved\": true, \"reviewer\": \"Lead Architect\"}");

        assertThat(resumeReport).contains("=== WORKFLOW RESUMPTION REPORT ===");
        assertThat(resumeReport).contains("Status: COMPLETED");
        assertThat(resumeReport).contains("Thread ID: " + threadId);
        assertThat(resumeReport).contains("\"approved\" : true");
        assertThat(resumeReport).contains("\"reviewer\" : \"Lead Architect\"");
        assertThat(resumeReport).contains("deploy_output");
    }

    @Test
    @DisplayName("Should handle invalid inputs, unknown IDs, and malformed JSON gracefully without throwing exceptions")
    void shouldHandleResilienceAndInvalidInputsGracefully() {
        // 1. Unknown workflowId
        String unknownWfReport = facade.executeWorkflow("non_existent_wf", "{}");
        assertThat(unknownWfReport).startsWith("Error: Workflow not found with ID: 'non_existent_wf'");

        // 2. Unknown threadId for resume
        String unknownThreadReport = facade.resumeWorkflow("non-existent-thread-123", "{}");
        assertThat(unknownThreadReport).startsWith("Error: No checkpoint found for threadId: 'non-existent-thread-123'");

        // 3. Malformed JSON in executeWorkflow
        String malformedExecReport = facade.executeWorkflow("research_pipeline", "{ invalid json [");
        assertThat(malformedExecReport).startsWith("Error: Invalid JSON format for initialStateJson:");

        // 4. Malformed JSON in resumeWorkflow
        String malformedResumeReport = facade.resumeWorkflow("any-thread", "{ invalid json [");
        assertThat(malformedResumeReport).startsWith("Error: Invalid JSON format for humanUpdatesJson:");

        // 5. Null or blank inputs
        assertThat(facade.executeWorkflow(null, "{}")).isEqualTo("Error: workflowId cannot be null or blank.");
        assertThat(facade.executeWorkflow("   ", "{}")).isEqualTo("Error: workflowId cannot be null or blank.");
        assertThat(facade.resumeWorkflow(null, "{}")).isEqualTo("Error: threadId cannot be null or blank to resume a workflow.");
        assertThat(facade.resumeWorkflow("   ", "{}")).isEqualTo("Error: threadId cannot be null or blank to resume a workflow.");

        // 6. Null or blank initialStateJson should be accepted as empty state
        String nullStateReport = facade.executeWorkflow("research_pipeline", null);
        assertThat(nullStateReport).contains("Status: COMPLETED");

        String emptyStateReport = facade.executeWorkflow("research_pipeline", "   ");
        assertThat(emptyStateReport).contains("Status: COMPLETED");
    }
}
