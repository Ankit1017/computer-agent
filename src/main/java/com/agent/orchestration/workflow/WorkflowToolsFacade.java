package com.agent.orchestration.workflow;

import com.agent.orchestration.workflow.model.WorkflowExecutionResult;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain4j Tool Facade exposing compiled DAG workflows as high-level, addressable tools
 * for the supervisor LLM orchestrator.
 *
 * <p>Enables the central orchestrator to delegate structured multi-agent pipelines to the
 * deterministic Bulk Synchronous Parallel (BSP) engine, state reducers, and checkpointing subsystem
 * without manual step-by-step dispatch.
 */
public class WorkflowToolsFacade {

    private static final Logger log = LoggerFactory.getLogger(WorkflowToolsFacade.class);

    private final WorkflowRegistryService registryService;
    private final ObjectMapper objectMapper;

    public WorkflowToolsFacade(WorkflowRegistryService registryService) {
        this(registryService, new ObjectMapper());
    }

    public WorkflowToolsFacade(WorkflowRegistryService registryService, ObjectMapper objectMapper) {
        this.registryService = Objects.requireNonNull(registryService, "registryService must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
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
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(title).append(" ===\n");
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
}
