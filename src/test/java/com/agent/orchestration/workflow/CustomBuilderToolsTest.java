package com.agent.orchestration.workflow;

import com.agent.config.AgentConfig;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.engine.WorkflowAgentInvoker;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import com.agent.registry.ToolRegistry;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomBuilderToolsTest {

    @TempDir
    Path tempDir;

    private SubAgentFactory subAgentFactory;
    private WorkflowRegistryService registryService;
    private WorkflowToolsFacade toolsFacade;

    @BeforeEach
    void setUp() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        ToolRegistry mockRegistry = mock(ToolRegistry.class);
        when(mockRegistry.getAvailableToolNames()).thenReturn(Set.of("terminal", "git", "web", "memory", "context", "vision"));
        when(mockRegistry.getTools(anyList())).thenReturn(new Object[0]);

        Path customAgentsPath = tempDir.resolve("custom_agents.json");
        subAgentFactory = new SubAgentFactory(mockModel, mockRegistry, customAgentsPath);

        registryService = new WorkflowRegistryService();
        registryService.setWorkflowsDir(tempDir.resolve("workflows"));

        // Setup mock invoker for workflow execution
        WorkflowAgentInvoker mockInvoker = (agentId, prompt) -> switch (agentId.toLowerCase()) {
            case "security_auditor" -> """
                    {
                      "audit_findings": ["Detected hardcoded secret"],
                      "status": "AUDITED"
                    }
                    """;
            case "developer" -> """
                    {
                      "patch": "Removed secret from codebase",
                      "status": "PATCHED"
                    }
                    """;
            case "devops" -> """
                    {
                      "commit_hash": "sha-abc-123",
                      "status": "DEPLOYED"
                    }
                    """;
            default -> "{\"output\": \"executed step\"}";
        };
        registryService.setAgentInvoker(mockInvoker);

        toolsFacade = new WorkflowToolsFacade(registryService, subAgentFactory);
    }

    @Test
    @DisplayName("Should dynamically create and hot-register custom agent with scoped tools and contracts")
    void shouldCreateAndRegisterCustomAgentAtRuntime() {
        String result = toolsFacade.createCustomAgent(
                "security_auditor",
                "Security Auditor",
                "Audit codebase for vulnerabilities and policy violations",
                "terminal, git",
                "repo_path, config",
                "audit_findings, severity"
        );

        assertThat(result)
                .contains("Successfully created and registered custom agent 'security_auditor'")
                .contains("Name: Security Auditor")
                .contains("Tools: [terminal, git]")
                .contains("Read Channels: [repo_path, config]")
                .contains("Write Channels: [audit_findings, severity]");

        // Verify registered in factory
        AgentConfig config = subAgentFactory.getConfig("security_auditor");
        assertThat(config).isNotNull();
        assertThat(config.id()).isEqualTo("security_auditor");
        assertThat(config.name()).isEqualTo("Security Auditor");
        assertThat(config.tools()).containsExactly("terminal", "git");
        assertThat(config.readChannels()).containsExactly("repo_path", "config");
        assertThat(config.writeChannels()).containsExactly("audit_findings", "severity");

        // Verify appears in listAvailableAgents
        String agentList = toolsFacade.listAvailableAgents();
        assertThat(agentList)
                .contains("=== REGISTERED SUB-AGENTS ===")
                .contains("- ID: security_auditor")
                .contains("  Name: Security Auditor")
                .contains("  Tools: [terminal, git]")
                .contains("  Read Channels: [repo_path, config]")
                .contains("  Write Channels: [audit_findings, severity]");
    }

    @Test
    @DisplayName("Should reject invalid tool categories with clear diagnostic error listing valid tools")
    void shouldRejectInvalidToolCategoryWithClearError() {
        String result = toolsFacade.createCustomAgent(
                "laser_bot",
                "Laser Bot",
                "Destroy bugs with laser",
                "quantum_laser, terminal",
                "",
                ""
        );

        assertThat(result)
                .startsWith("Error: Unknown tool category requested: 'quantum_laser'")
                .contains("Valid tool categories: [");

        // Ensure agent was not registered
        assertThat(subAgentFactory.getConfig("laser_bot")).isNull();
    }

    @Test
    @DisplayName("Should create linear sequence with auto-generated edges, persist to disk, and execute end-to-end")
    void shouldCreateAndExecuteCustomLinearSequence() {
        // First register the agents used in the sequence
        toolsFacade.createCustomAgent("security_auditor", "Auditor", "Audit code", "terminal, git", "", "audit_findings");
        toolsFacade.createCustomAgent("developer", "Dev", "Write code", "terminal", "", "patch");
        toolsFacade.createCustomAgent("devops", "Ops", "Deploy code", "git", "", "commit_hash");

        String stepsJson = """
                [
                  {
                    "nodeId": "audit_step",
                    "agentId": "security_auditor",
                    "taskInstruction": "Inspect repository for secrets",
                    "writeChannels": ["audit_findings"]
                  },
                  {
                    "agentId": "developer",
                    "taskInstruction": "Apply patch removing secret"
                  },
                  {
                    "agentId": "devops",
                    "taskInstruction": "Commit and deploy patch"
                  }
                ]
                """;

        String createResult = toolsFacade.createCustomSequence(
                "auto_remediation_pipeline",
                "Automated Security Remediation Pipeline",
                stepsJson,
                "audit_findings, notes"
        );

        assertThat(createResult)
                .contains("Successfully created and registered sequence 'auto_remediation_pipeline'")
                .contains("Total Steps: 3")
                .contains("Edges: 3");

        // Verify graph was registered in registry
        WorkflowGraph graph = registryService.getWorkflow("auto_remediation_pipeline").orElseThrow();
        assertThat(graph.workflowId()).isEqualTo("auto_remediation_pipeline");
        assertThat(graph.nodes()).hasSize(3);
        assertThat(graph.nodes().get(0).nodeId()).isEqualTo("audit_step");
        assertThat(graph.nodes().get(1).nodeId()).isEqualTo("step_2"); // auto-generated
        assertThat(graph.nodes().get(2).nodeId()).isEqualTo("step_3"); // auto-generated

        // Verify edge transitions: audit_step -> step_2 -> step_3 -> __END__
        assertThat(graph.edges()).hasSize(3);
        assertThat(graph.edges().get(0).sourceNode()).isEqualTo("audit_step");
        assertThat(graph.edges().get(0).targetNode()).isEqualTo("step_2");
        assertThat(graph.edges().get(1).sourceNode()).isEqualTo("step_2");
        assertThat(graph.edges().get(1).targetNode()).isEqualTo("step_3");
        assertThat(graph.edges().get(2).sourceNode()).isEqualTo("step_3");
        assertThat(graph.edges().get(2).targetNode()).isEqualTo(WorkflowGraph.END_NODE);

        // Verify persisted to disk
        Path persistedFile = tempDir.resolve("workflows/auto_remediation_pipeline.json");
        assertThat(Files.exists(persistedFile)).isTrue();

        // Execute the registered sequence end-to-end via executeWorkflow tool
        String execReport = toolsFacade.executeWorkflow("auto_remediation_pipeline", "{\"repo_path\": \"/workspace/app\"}");
        assertThat(execReport)
                .contains("=== WORKFLOW EXECUTION REPORT ===")
                .contains("Workflow ID: auto_remediation_pipeline")
                .contains("Status: COMPLETED")
                .contains("Detected hardcoded secret")
                .contains("Removed secret from codebase")
                .contains("sha-abc-123");
    }

    @Test
    @DisplayName("Should create custom full DAG workflow, validate malformed graphs, and execute valid DAGs")
    void shouldCreateAndExecuteCustomDagWorkflowAndValidateMalformedGraphs() {
        // 1. Malformed JSON test (missing required startNodeId, orphaned node)
        String malformedGraphJson = """
                {
                  "workflowId": "invalid_dag",
                  "description": "Invalid DAG with orphaned node",
                  "startNodeId": "non_existent_start",
                  "nodes": [
                    { "nodeId": "node1", "agentId": "developer" },
                    { "nodeId": "orphaned_node", "agentId": "developer" }
                  ],
                  "edges": [
                    { "sourceNode": "node1", "targetNode": "__END__" }
                  ]
                }
                """;

        String errorResult = toolsFacade.createCustomWorkflow(malformedGraphJson);
        assertThat(errorResult)
                .startsWith("Error:")
                .contains("Start node 'non_existent_start' does not exist in the defined nodes");

        // 2. Valid full DAG workflow (with parallel fan-out and join)
        String validDagJson = """
                {
                  "workflowId": "fanout_dag",
                  "description": "Parallel Fanout Pipeline",
                  "startNodeId": "start_node",
                  "nodes": [
                    { "nodeId": "start_node", "agentId": "security_auditor", "taskInstruction": "Audit" },
                    { "nodeId": "branch_dev", "agentId": "developer", "taskInstruction": "Develop" },
                    { "nodeId": "branch_ops", "agentId": "devops", "taskInstruction": "Deploy" }
                  ],
                  "edges": [
                    { "sourceNode": "start_node", "targetNode": "branch_dev" },
                    { "sourceNode": "start_node", "targetNode": "branch_ops" },
                    { "sourceNode": "branch_dev", "targetNode": "__END__" },
                    { "sourceNode": "branch_ops", "targetNode": "__END__" }
                  ]
                }
                """;

        String successResult = toolsFacade.createCustomWorkflow(validDagJson);
        assertThat(successResult)
                .contains("Successfully created and registered custom workflow 'fanout_dag'")
                .contains("Start Node: start_node")
                .contains("Total Nodes: 3")
                .contains("Total Edges: 4");

        // Verify persisted to disk
        Path persistedFile = tempDir.resolve("workflows/fanout_dag.json");
        assertThat(Files.exists(persistedFile)).isTrue();

        // Execute valid DAG workflow
        String execReport = toolsFacade.executeWorkflow("fanout_dag", "{}");
        assertThat(execReport)
                .contains("Workflow ID: fanout_dag")
                .contains("Status: COMPLETED");
    }

    @Test
    @DisplayName("Should persist and reload custom agents across factory restarts")
    void shouldPersistAndReloadCustomAgentsAcrossRestarts() {
        toolsFacade.createCustomAgent(
                "persistent_agent",
                "Persistent Agent",
                "Persists across restarts",
                "terminal, web",
                "input_channel",
                "output_channel"
        );

        Path customAgentsFile = tempDir.resolve("custom_agents.json");
        assertThat(Files.exists(customAgentsFile)).isTrue();

        // Instantiate fresh SubAgentFactory pointing to the same custom agents file
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        ToolRegistry mockRegistry = mock(ToolRegistry.class);
        when(mockRegistry.getAvailableToolNames()).thenReturn(Set.of("terminal", "web"));
        when(mockRegistry.getTools(anyList())).thenReturn(new Object[0]);

        SubAgentFactory freshFactory = new SubAgentFactory(mockModel, mockRegistry, customAgentsFile);
        AgentConfig reloadedConfig = freshFactory.getConfig("persistent_agent");
        assertThat(reloadedConfig).isNotNull();
        assertThat(reloadedConfig.id()).isEqualTo("persistent_agent");
        assertThat(reloadedConfig.name()).isEqualTo("Persistent Agent");
        assertThat(reloadedConfig.tools()).containsExactly("terminal", "web");
        assertThat(reloadedConfig.readChannels()).containsExactly("input_channel");
        assertThat(reloadedConfig.writeChannels()).containsExactly("output_channel");
    }
}
