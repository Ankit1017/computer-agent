package com.agent.orchestration;

import com.agent.WorkerAgent;
import com.agent.AgentMain;
import com.agent.config.AgentConfig;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.services.OrchestrationService;
import com.agent.orchestration.workflow.WorkflowToolsFacade;
import com.agent.orchestration.workflow.checkpoint.JsonFileCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpoint;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import com.agent.tools.context.services.BlackboardService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HybridOrchestratorIntegrationTest {

    @TempDir
    Path tempCheckpointDir;

    private JsonFileCheckpointer checkpointer;
    private BlackboardService blackboardService;
    private SubAgentFactory subAgentFactory;
    private OrchestrationService orchestrationService;
    private WorkflowRegistryService workflowRegistry;
    private WorkflowToolsFacade workflowToolsFacade;
    private DelegationFacade delegationFacade;

    @BeforeEach
    void setUp() {
        checkpointer = new JsonFileCheckpointer(tempCheckpointDir);
        blackboardService = new BlackboardService();
        subAgentFactory = mock(SubAgentFactory.class);

        // Configure mock sub-agents
        when(subAgentFactory.getAgent("developer")).thenReturn(task -> "{\"code\": \"Class PaymentService created\"}");
        when(subAgentFactory.getAgent("architect")).thenReturn(task -> "{\"arch\": \"Microservice design validated\"}");
        when(subAgentFactory.getAgent("supervisor")).thenReturn(task -> "{\"review\": \"Gate inspected\"}");
        when(subAgentFactory.getAgent("devops")).thenReturn(task -> "{\"deploy\": \"Deployed v2.0 to Kubernetes\"}");
        when(subAgentFactory.getAgent("researcher")).thenReturn(task -> "{\"research\": \"Market research compiled\"}");

        orchestrationService = new OrchestrationService(subAgentFactory, blackboardService);
        workflowRegistry = new WorkflowRegistryService(orchestrationService, checkpointer);
        workflowToolsFacade = new WorkflowToolsFacade(workflowRegistry);
        delegationFacade = new DelegationFacade(orchestrationService);
    }

    @Test
    @DisplayName("Should expose and execute complete hybrid lifecycle: catalog discovery, workflow execution, disk checkpointing, suspension, and resumption")
    void shouldExecuteFullHybridWorkflowLifecycle() throws IOException {
        // 1. Catalog Discovery
        String catalog = workflowToolsFacade.listAvailableWorkflows();
        assertThat(catalog).contains("=== REGISTERED WORKFLOW CATALOG ===");
        assertThat(catalog).contains("research_pipeline");

        // 2. Register multi-stage release workflow with HITL approval gate
        WorkflowNode n1 = new WorkflowNode("prep", "developer", "Generate payment service");
        WorkflowNode n2 = new WorkflowNode("review_gate", "supervisor", "Review deployment spec", false, true); // interruptAfter
        WorkflowNode n3 = new WorkflowNode("deploy", "devops", "Deploy to production");

        WorkflowEdge e1 = new WorkflowEdge("prep", "review_gate");
        WorkflowEdge e2 = new WorkflowEdge("review_gate", "deploy");
        WorkflowEdge e3 = new WorkflowEdge("deploy", WorkflowGraph.END_NODE);

        WorkflowGraph releaseGraph = new WorkflowGraph(
                "release_pipeline",
                "Automated production release with human signoff",
                "prep",
                List.of(n1, n2, n3),
                List.of(e1, e2, e3),
                List.of("audit_log")
        );

        workflowRegistry.registerWorkflow(releaseGraph);

        // 3. Execute workflow via WorkflowToolsFacade -> Suspends at review_gate
        String execReport = workflowToolsFacade.executeWorkflow("release_pipeline", "{\"service\": \"payments\"}");

        assertThat(execReport).contains("=== WORKFLOW EXECUTION REPORT ===");
        assertThat(execReport).contains("Workflow ID: release_pipeline");
        assertThat(execReport).contains("Status: SUSPENDED");
        assertThat(execReport).contains("Interrupted Nodes: [review_gate]");
        assertThat(execReport).contains("PaymentService created");
        assertThat(execReport).doesNotContain("Deployed v2.0");

        // Extract auto-generated threadId
        Matcher matcher = Pattern.compile("Thread ID:\\s*([a-fA-F0-9\\-]+)").matcher(execReport);
        assertThat(matcher.find()).isTrue();
        String threadId = matcher.group(1).trim();

        // 4. Verify disk persistence: Checkpoint file exists in tempCheckpointDir
        try (var stream = Files.walk(tempCheckpointDir)) {
            boolean hasJsonCheckpoint = stream
                    .anyMatch(p -> p.getFileName().toString().startsWith("checkpoint_step_") && p.toString().endsWith(".json"));
            assertThat(hasJsonCheckpoint).isTrue();
        }

        // Verify loaded checkpoint status from disk
        Optional<WorkflowCheckpoint> diskCheckpointOpt = checkpointer.loadLatest(threadId);
        assertThat(diskCheckpointOpt).isPresent();
        WorkflowCheckpoint diskCheckpoint = diskCheckpointOpt.get();
        assertThat(diskCheckpoint.status()).isEqualTo(WorkflowExecutionStatus.SUSPENDED);
        assertThat(diskCheckpoint.interruptedNodes()).contains("review_gate");

        // 5. Human-in-the-Loop Resumption via WorkflowToolsFacade
        String resumeReport = workflowToolsFacade.resumeWorkflow(
                threadId,
                "{\"approved\": true, \"signer\": \"Release Manager\"}"
        );

        assertThat(resumeReport).contains("=== WORKFLOW RESUMPTION REPORT ===");
        assertThat(resumeReport).contains("Workflow ID: release_pipeline");
        assertThat(resumeReport).contains("Status: COMPLETED");
        assertThat(resumeReport).contains("\"approved\" : true");
        assertThat(resumeReport).contains("\"signer\" : \"Release Manager\"");
        assertThat(resumeReport).contains("Deployed v2.0 to Kubernetes");

        // Verify final checkpoint reflects COMPLETED state on disk
        Optional<WorkflowCheckpoint> finalDiskCheckpointOpt = checkpointer.loadLatest(threadId);
        assertThat(finalDiskCheckpointOpt).isPresent();
        assertThat(finalDiskCheckpointOpt.get().status()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
    }

    @Test
    @DisplayName("Should execute dynamic delegation tools alongside deterministic workflow tools in hybrid environment")
    void shouldExecuteDynamicDelegationConcurrently() {
        // Dynamic delegation works directly through delegationFacade
        String devResult = delegationFacade.delegateTask("developer", "Implement repository layer");
        assertThat(devResult).contains("PaymentService created");

        // Parallel delegation
        String parallelResult = delegationFacade.delegateParallel(
                "developer", "Task 1",
                "architect", "Task 2"
        );
        assertThat(parallelResult).contains("PaymentService created");
        assertThat(parallelResult).contains("Microservice design validated");
    }

    @Test
    @DisplayName("Should build Hybrid Orchestrator in AgentMain with both DelegationFacade and WorkflowToolsFacade")
    void shouldBuildHybridOrchestratorInAgentMain() {
        ChatLanguageModel modelMock = mock(ChatLanguageModel.class);
        List<AgentConfig> configs = List.of(
                new AgentConfig("developer", "Senior Developer", "Write code", List.of("terminal")),
                new AgentConfig("architect", "Lead Architect", "Design systems", List.of("terminal"))
        );

        WorkerAgent orchestrator = AgentMain.buildOrchestrator(
                modelMock,
                orchestrationService,
                workflowToolsFacade,
                configs,
                workflowRegistry
        );

        assertThat(orchestrator).isNotNull();
    }
}
