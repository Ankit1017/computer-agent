package com.agent.orchestration.workflow;

import com.agent.config.AgentConfig;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.orchestration.workflow.model.SequenceStep;
import com.agent.orchestration.workflow.model.WorkflowExecutionResult;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain4j Tool Facade exposing compiled DAG workflows and runtime builder tools
 * as high-level, addressable tools for the supervisor LLM orchestrator.
 *
 * <p>Enables the central orchestrator to delegate structured multi-agent pipelines to the
 * deterministic Bulk Synchronous Parallel (BSP) engine, state reducers, and checkpointing subsystem
 * as well as dynamically create custom specialist agents, sequences, and workflows on the fly.
 */
public class WorkflowToolsFacade {

    private static final Logger log = LoggerFactory.getLogger(WorkflowToolsFacade.class);

    private final WorkflowRegistryService registryService;
    private final ObjectMapper objectMapper;
    private SubAgentFactory agentFactory;

    public WorkflowToolsFacade(WorkflowRegistryService registryService) {
        this(registryService, (SubAgentFactory) null, new ObjectMapper());
    }

    public WorkflowToolsFacade(WorkflowRegistryService registryService, ObjectMapper objectMapper) {
        this(registryService, (SubAgentFactory) null, objectMapper);
    }

    public WorkflowToolsFacade(WorkflowRegistryService registryService, SubAgentFactory agentFactory) {
        this(registryService, agentFactory, new ObjectMapper());
    }

    public WorkflowToolsFacade(WorkflowRegistryService registryService, SubAgentFactory agentFactory, ObjectMapper objectMapper) {
        this.registryService = Objects.requireNonNull(registryService, "registryService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.agentFactory = agentFactory;
    }

    /**
     * Lists all declarative workflows available in the catalog along with their starting node,
     * agent tasks, and flow topology.
     *
     * @return formatted catalog string
     */
    @Tool("List all available declarative workflows and pipelines in the catalog, including their nodes, agents, and topology.")
    public String listAvailableWorkflows() {
        try {
            return registryService.getCatalogSummary();
        } catch (Exception e) {
            log.error("[WORKFLOW-TOOL] Error retrieving catalog: {}", e.getMessage(), e);
            return "Error retrieving workflow catalog: " + e.getMessage();
        }
    }

    /**
     * Executes a registered workflow by ID with an optional initial state JSON payload.
     *
     * @param workflowId       unique identifier of the workflow to run
     * @param initialStateJson optional JSON string of initial key-value parameters
     * @return formatted execution outcome report
     */
    @Tool("Execute a registered declarative workflow by ID with an optional initial state JSON payload.")
    public String executeWorkflow(
            @P("The unique identifier of the workflow to execute (e.g., 'research_pipeline')") String workflowId,
            @P("Optional JSON string representing the initial state key-value pairs (e.g., '{\"topic\": \"Java 21\"}')") String initialStateJson
    ) {
        if (workflowId == null || workflowId.isBlank()) {
            return "Error: workflowId cannot be null or blank.";
        }

        Map<String, Object> initialState;
        try {
            initialState = parseJsonState(initialStateJson);
        } catch (Exception e) {
            return "Error: Invalid JSON format for initialStateJson: " + e.getMessage();
        }

        try {
            WorkflowExecutionResult result = registryService.executeWorkflow(workflowId.trim(), initialState);
            return formatExecutionReport("WORKFLOW EXECUTION REPORT", result);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("[WORKFLOW-TOOL] Execution failed for workflow '{}': {}", workflowId, e.getMessage(), e);
            return "Error executing workflow '" + workflowId + "': " + e.getMessage();
        }
    }

    /**
     * Resumes a paused or suspended workflow execution by thread ID with optional human-in-the-loop state updates.
     *
     * @param threadId         unique thread identifier of the suspended workflow
     * @param humanUpdatesJson optional JSON string representing human feedback or state overrides
     * @return formatted resumption outcome report
     */
    @Tool("Resume a paused or suspended workflow execution by thread ID with optional human-in-the-loop state updates.")
    public String resumeWorkflow(
            @P("The execution thread ID of the paused workflow run to resume") String threadId,
            @P("Optional JSON string representing human-in-the-loop state updates (e.g., '{\"approved\": true}')") String humanUpdatesJson
    ) {
        if (threadId == null || threadId.isBlank()) {
            return "Error: threadId cannot be null or blank to resume a workflow.";
        }

        Map<String, Object> humanUpdates;
        try {
            humanUpdates = parseJsonState(humanUpdatesJson);
        } catch (Exception e) {
            return "Error: Invalid JSON format for humanUpdatesJson: " + e.getMessage();
        }

        try {
            WorkflowExecutionResult result = registryService.resumeWorkflow(threadId.trim(), humanUpdates);
            return formatExecutionReport("WORKFLOW RESUMPTION REPORT", result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("[WORKFLOW-TOOL] Resumption failed for thread '{}': {}", threadId, e.getMessage(), e);
            return "Error resuming workflow thread '" + threadId + "': " + e.getMessage();
        }
    }

    private Map<String, Object> parseJsonState(String json) throws Exception {
        if (json == null || json.isBlank() || "null".equalsIgnoreCase(json.trim())) {
            return Collections.emptyMap();
        }
        return objectMapper.readValue(json.trim(), new TypeReference<Map<String, Object>>() {});
    }

    private String formatExecutionReport(String title, WorkflowExecutionResult result) {
        String reportTitle = (result != null && result.status() == WorkflowExecutionStatus.ROLLED_BACK)
                ? "WORKFLOW SAGA ROLLBACK REPORT"
                : title;
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(reportTitle).append(" ===\n");
        sb.append("Workflow ID: ").append(result.workflowId()).append("\n");
        sb.append("Thread ID: ").append(result.threadId()).append("\n");
        sb.append("Status: ").append(result.status()).append("\n");
        if (!result.interruptedNodes().isEmpty()) {
            sb.append("Interrupted Nodes: ").append(result.interruptedNodes()).append("\n");
        }
        if (result.message() != null && !result.message().isBlank()) {
            sb.append("Message: ").append(result.message()).append("\n");
        }
        sb.append("\nState Snapshot:\n");
        try {
            sb.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.stateData()));
        } catch (Exception e) {
            sb.append(result.stateData().toString());
        }
        sb.append("\n=================================");
        return sb.toString();
    }

    public WorkflowRegistryService getRegistryService() {
        return registryService;
    }

    public SubAgentFactory getSubAgentFactory() {
        return agentFactory;
    }

    public void setSubAgentFactory(SubAgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /**
     * Lists all registered sub-agents in the swarm, including their ID, name, assigned tools, and scoped state contracts.
     *
     * @return formatted summary string of available agents
     */
    @Tool("List all available registered sub-agents in the swarm, including their ID, name, assigned tools, and scoped read/write channels.")
    public String listAvailableAgents() {
        if (agentFactory == null) {
            return "No SubAgentFactory configured on WorkflowToolsFacade.";
        }
        Map<String, AgentConfig> configs = agentFactory.getConfigs();
        if (configs == null || configs.isEmpty()) {
            return "No sub-agents currently registered in the swarm.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== REGISTERED SUB-AGENTS ===\n");
        for (AgentConfig config : configs.values()) {
            sb.append("- ID: ").append(config.id()).append("\n");
            sb.append("  Name: ").append(config.name()).append("\n");
            sb.append("  Tools: ").append(config.tools()).append("\n");
            if (config.readChannels() != null && !config.readChannels().isEmpty()) {
                sb.append("  Read Channels: ").append(config.readChannels()).append("\n");
            }
            if (config.writeChannels() != null && !config.writeChannels().isEmpty()) {
                sb.append("  Write Channels: ").append(config.writeChannels()).append("\n");
            }
            if (config.systemMessage() != null && !config.systemMessage().isBlank()) {
                sb.append("  Mission: ").append(config.systemMessage()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("=============================");
        return sb.toString().trim();
    }

    /**
     * Creates and hot-registers a custom specialist agent dynamically at runtime with scoped tools and state contracts.
     *
     * @param agentId          unique identifier for the agent (e.g., 'security_auditor')
     * @param name             human-readable name (e.g., 'Security Auditor')
     * @param systemMessage    system mission and instructions for the agent
     * @param toolsCsv         comma-separated list of assigned tool categories (e.g., 'terminal, git, memory')
     * @param readChannelsCsv  optional comma-separated list of read channels (e.g., 'code, config')
     * @param writeChannelsCsv optional comma-separated list of write channels (e.g., 'vulnerabilities, audit_report')
     * @return confirmation report or descriptive error string
     */
    @Tool("Create and hot-register a new custom specialist agent dynamically at runtime with scoped tools and state contracts.")
    public String createCustomAgent(
            @P("Unique identifier for the agent (e.g., 'security_auditor')") String agentId,
            @P("Human-readable name (e.g., 'Security Auditor')") String name,
            @P("System mission and instructions for the agent") String systemMessage,
            @P("Comma-separated list of assigned tool categories (e.g., 'terminal, git, memory')") String toolsCsv,
            @P("Optional comma-separated list of read channels (e.g., 'code, config')") String readChannelsCsv,
            @P("Optional comma-separated list of write channels (e.g., 'vulnerabilities, audit_report')") String writeChannelsCsv
    ) {
        if (agentFactory == null) {
            return "Error: SubAgentFactory is not configured on WorkflowToolsFacade.";
        }
        if (agentId == null || agentId.isBlank()) {
            return "Error: agentId cannot be null or blank.";
        }
        if (name == null || name.isBlank()) {
            return "Error: name cannot be null or blank.";
        }
        if (systemMessage == null || systemMessage.isBlank()) {
            return "Error: systemMessage cannot be null or blank.";
        }
        if (toolsCsv == null || toolsCsv.isBlank()) {
            return "Error: toolsCsv cannot be null or blank. Valid tool categories: " + SubAgentFactory.VALID_TOOL_CATEGORIES;
        }

        List<String> tools = parseCsv(toolsCsv);
        List<String> readChannels = parseCsv(readChannelsCsv);
        List<String> writeChannels = parseCsv(writeChannelsCsv);

        AgentConfig config = new AgentConfig(
                agentId.trim(),
                name.trim(),
                systemMessage.trim(),
                tools,
                readChannels,
                writeChannels
        );

        try {
            agentFactory.createCustomAgent(config);
            StringBuilder sb = new StringBuilder();
            sb.append("Successfully created and registered custom agent '").append(config.id()).append("'.\n");
            sb.append("Name: ").append(config.name()).append("\n");
            sb.append("Tools: ").append(config.tools()).append("\n");
            if (!config.readChannels().isEmpty()) {
                sb.append("Read Channels: ").append(config.readChannels()).append("\n");
            }
            if (!config.writeChannels().isEmpty()) {
                sb.append("Write Channels: ").append(config.writeChannels()).append("\n");
            }
            return sb.toString().trim();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("[WORKFLOW-TOOL] Failed to create custom agent '{}': {}", agentId, e.getMessage(), e);
            return "Error creating custom agent '" + agentId + "': " + e.getMessage();
        }
    }

    /**
     * Creates and registers a custom linear sequence workflow dynamically at runtime from an ordered JSON array of step definitions.
     *
     * @param workflowId       unique workflow identifier (e.g., 'audit_pipeline')
     * @param description      optional human-readable description of the sequence
     * @param stepsJson        JSON array of step objects, e.g. '[{"agentId": "security_auditor", "taskInstruction": "Audit code"}]'
     * @param listChannelsCsv  optional comma-separated list of accumulation list channels (e.g., 'notes, findings')
     * @return confirmation report or descriptive error string
     */
    @Tool("Create and register a custom linear sequence workflow dynamically at runtime from an ordered JSON array of step definitions.")
    public String createCustomSequence(
            @P("Unique workflow identifier (e.g., 'audit_pipeline')") String workflowId,
            @P("Optional human-readable description of the sequence") String description,
            @P("JSON array of step objects, e.g. '[{\"agentId\": \"security_auditor\", \"taskInstruction\": \"Audit code\"}, ...]'") String stepsJson,
            @P("Optional comma-separated list of accumulation list channels (e.g., 'notes, findings')") String listChannelsCsv
    ) {
        if (workflowId == null || workflowId.isBlank()) {
            return "Error: workflowId cannot be null or blank.";
        }
        if (stepsJson == null || stepsJson.isBlank()) {
            return "Error: stepsJson cannot be null or blank.";
        }

        List<SequenceStep> steps;
        try {
            steps = objectMapper.readValue(stepsJson.trim(), new TypeReference<List<SequenceStep>>() {});
        } catch (Exception e) {
            return "Error: Invalid JSON format for stepsJson: " + e.getMessage();
        }

        List<String> listChannels = parseCsv(listChannelsCsv);

        try {
            var graph = registryService.createAndRegisterSequence(workflowId.trim(), description, listChannels, steps);
            StringBuilder sb = new StringBuilder();
            sb.append("Successfully created and registered sequence '").append(graph.workflowId()).append("'.\n");
            sb.append("Total Steps: ").append(graph.nodes().size()).append("\n");
            sb.append("Edges: ").append(graph.edges().size()).append("\n");
            sb.append("Ready for execution via executeWorkflow('").append(graph.workflowId()).append("', ...).");
            return sb.toString().trim();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("[WORKFLOW-TOOL] Failed to create custom sequence '{}': {}", workflowId, e.getMessage(), e);
            return "Error creating custom sequence '" + workflowId + "': " + e.getMessage();
        }
    }

    /**
     * Creates and registers a custom DAG workflow dynamically at runtime from a complete WorkflowGraph JSON specification.
     *
     * @param workflowGraphJson complete JSON string representing the WorkflowGraph
     * @return confirmation report or descriptive error string
     */
    @Tool("Create and register a custom DAG workflow dynamically at runtime from a complete WorkflowGraph JSON specification.")
    public String createCustomWorkflow(
            @P("Complete JSON string representing the WorkflowGraph (with workflowId, startNodeId, nodes, edges, etc.)") String workflowGraphJson
    ) {
        if (workflowGraphJson == null || workflowGraphJson.isBlank()) {
            return "Error: workflowGraphJson cannot be null or blank.";
        }

        try {
            var graph = registryService.createAndRegisterWorkflow(workflowGraphJson.trim());
            StringBuilder sb = new StringBuilder();
            sb.append("Successfully created and registered custom workflow '").append(graph.workflowId()).append("'.\n");
            sb.append("Start Node: ").append(graph.startNodeId()).append("\n");
            sb.append("Total Nodes: ").append(graph.nodes().size()).append("\n");
            sb.append("Total Edges: ").append(graph.edges().size()).append("\n");
            sb.append("Ready for execution via executeWorkflow('").append(graph.workflowId()).append("', ...).");
            return sb.toString().trim();
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.error("[WORKFLOW-TOOL] Failed to create custom workflow: {}", e.getMessage(), e);
            return "Error creating custom workflow: " + e.getMessage();
        }
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
