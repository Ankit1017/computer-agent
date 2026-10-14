package com.agent.orchestration.workflow.services;

import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.services.OrchestrationService;
import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpoint;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.orchestration.workflow.engine.WorkflowAgentInvoker;
import com.agent.orchestration.workflow.engine.WorkflowEngine;
import com.agent.orchestration.workflow.model.SequenceStep;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowExecutionResult;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.state.AppendListReducer;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry and execution coordinator for declarative workflow definitions (DAGs).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li><b>Discovery & Registration:</b> Register workflows programmatically or load JSON workflow
 *       manifests from classpath resources (e.g. {@code workflows/research_pipeline.json}) and disk directories.</li>
 *   <li><b>Execution Coordination:</b> Dispatches workflow runs via {@link WorkflowEngine}, backed by
 *       {@link WorkflowAgentInvoker} (preserving Core Memory auto-injection via {@link OrchestrationService}
 *       or {@link SubAgentFactory}) and durable {@link WorkflowCheckpointer}.</li>
 *   <li><b>Channel Reducer Configuration:</b> Automatically wires standard accumulation channels
 *       ({@code notes}, {@code findings}, {@code artifacts}, {@code logs}) and custom declared list channels
 *       to {@link AppendListReducer}, defaulting scalar fields to overwrite reduction.</li>
 *   <li><b>Catalog Inspection:</b> Generates human- and LLM-readable summaries of registered workflows,
 *       their constituent agent roles, and branching topology.</li>
 *   <li><b>Suspension & Resumption:</b> Drives cooperative Human-in-the-Loop (HITL) checkpoints and thread resumption.</li>
 * </ul>
 */
public class WorkflowRegistryService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistryService.class);

    public static final Set<String> DEFAULT_LIST_CHANNELS = Set.of("notes", "findings", "artifacts", "logs");

    private final Map<String, WorkflowGraph> registeredWorkflows = new ConcurrentHashMap<>();
    private final WorkflowParserService parserService;
    private final ObjectMapper objectMapper;
    private WorkflowAgentInvoker agentInvoker;
    private WorkflowCheckpointer checkpointer;
    private java.util.function.Function<String, com.agent.config.AgentConfig> configResolver;
    private Path workflowsDir;

    public WorkflowRegistryService() {
        this((WorkflowAgentInvoker) null, new InMemoryCheckpointer(), new WorkflowParserService());
    }

    public WorkflowRegistryService(WorkflowAgentInvoker agentInvoker) {
        this(agentInvoker, new InMemoryCheckpointer(), new WorkflowParserService());
    }

    public WorkflowRegistryService(WorkflowAgentInvoker agentInvoker, WorkflowCheckpointer checkpointer) {
        this(agentInvoker, checkpointer, new WorkflowParserService());
    }

    public WorkflowRegistryService(WorkflowAgentInvoker agentInvoker, WorkflowCheckpointer checkpointer, WorkflowParserService parserService) {
        this.agentInvoker = agentInvoker;
        this.checkpointer = checkpointer != null ? checkpointer : new InMemoryCheckpointer();
        this.parserService = parserService != null ? parserService : new WorkflowParserService();
        this.objectMapper = new ObjectMapper();
        this.configResolver = agentInvoker != null ? agentInvoker::getConfig : null;
        discoverDefaultWorkflows();
    }

    public WorkflowRegistryService(SubAgentFactory agentFactory) {
        this(agentFactory, new InMemoryCheckpointer(), new WorkflowParserService());
    }

    public WorkflowRegistryService(SubAgentFactory agentFactory, WorkflowCheckpointer checkpointer) {
        this(agentFactory, checkpointer, new WorkflowParserService());
    }

    public WorkflowRegistryService(SubAgentFactory agentFactory, WorkflowCheckpointer checkpointer, WorkflowParserService parserService) {
        this(WorkflowEngine.adaptAgentFactory(agentFactory), checkpointer, parserService);
        this.configResolver = agentFactory != null ? agentFactory::getConfig : null;
    }

    public WorkflowRegistryService(OrchestrationService orchestrationService) {
        this(orchestrationService, new InMemoryCheckpointer(), new WorkflowParserService());
    }

    public WorkflowRegistryService(OrchestrationService orchestrationService, WorkflowCheckpointer checkpointer) {
        this(orchestrationService, checkpointer, new WorkflowParserService());
    }

    public WorkflowRegistryService(OrchestrationService orchestrationService, WorkflowCheckpointer checkpointer, WorkflowParserService parserService) {
        this(adaptOrchestrationService(orchestrationService), checkpointer, parserService);
        this.configResolver = agentId -> orchestrationService != null && orchestrationService.getAgentFactory() != null
                ? orchestrationService.getAgentFactory().getConfig(agentId)
                : null;
    }

    /**
     * Adapts an {@link OrchestrationService} into a {@link WorkflowAgentInvoker}, preserving
     * Core Memory auto-injection, retry resilience, and AgentConfig contract lookup.
     */
    public static WorkflowAgentInvoker adaptOrchestrationService(OrchestrationService orchestrationService) {
        Objects.requireNonNull(orchestrationService, "orchestrationService must not be null");
        return new WorkflowAgentInvoker() {
            @Override
            public String invoke(String agentId, String taskPrompt) {
                return orchestrationService.routeTask(agentId, taskPrompt);
            }

            @Override
            public com.agent.config.AgentConfig getConfig(String agentId) {
                if (orchestrationService.getAgentFactory() != null) {
                    return orchestrationService.getAgentFactory().getConfig(agentId);
                }
                return null;
            }
        };
    }

    /**
     * Attempts default discovery of common workflow locations:
     * classpath "workflows/research_pipeline.json", and local filesystem "workflows" directories.
     */
    public final void discoverDefaultWorkflows() {
        try {
            loadFromResource("workflows/research_pipeline.json");
        } catch (Exception e) {
            log.debug("Default classpath workflow 'workflows/research_pipeline.json' not present: {}", e.getMessage());
        }

        try {
            loadFromDirectory(Path.of("workflows"));
        } catch (Exception e) {
            log.debug("Local 'workflows/' directory not present: {}", e.getMessage());
        }

        try {
            loadFromDirectory(Path.of("src/main/resources/workflows"));
        } catch (Exception e) {
            log.debug("Resource directory 'src/main/resources/workflows' not scanned: {}", e.getMessage());
        }
    }

    /**
     * Registers a workflow definition programmatically.
     *
     * @param graph validated workflow graph
     */
    public void registerWorkflow(WorkflowGraph graph) {
        Objects.requireNonNull(graph, "WorkflowGraph must not be null");
        Objects.requireNonNull(graph.workflowId(), "workflowId must not be null");
        String key = graph.workflowId().trim().toLowerCase();
        registeredWorkflows.put(key, graph);
        log.info("[REGISTRY] Registered workflow '{}' with {} nodes and {} edges",
                graph.workflowId(), graph.nodes().size(), graph.edges().size());
    }

    /**
     * Persists a workflow graph to the configured workflowsDir if configured.
     *
     * @param graph validated workflow graph
     */
    public void persistWorkflow(WorkflowGraph graph) {
        if (workflowsDir == null || graph == null) {
            return;
        }
        try {
            if (!Files.exists(workflowsDir)) {
                Files.createDirectories(workflowsDir);
            }
            Path targetFile = workflowsDir.resolve(graph.workflowId() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(targetFile.toFile(), graph);
            log.info("[REGISTRY] Persisted workflow '{}' to: {}", graph.workflowId(), targetFile.toAbsolutePath());
        } catch (Exception e) {
            log.warn("[REGISTRY] Failed to persist workflow '{}' to '{}': {}", graph.workflowId(), workflowsDir, e.getMessage());
        }
    }

    /**
     * Parses, validates, and hot-registers a full custom DAG workflow from JSON,
     * persisting to disk if workflowsDir is configured.
     *
     * @param workflowGraphJson complete JSON string of the WorkflowGraph
     * @return validated and registered WorkflowGraph
     */
    public WorkflowGraph createAndRegisterWorkflow(String workflowGraphJson) {
        if (workflowGraphJson == null || workflowGraphJson.isBlank()) {
            throw new IllegalArgumentException("workflowGraphJson must not be null or blank");
        }
        WorkflowGraph graph = parserService.parse(workflowGraphJson.trim());
        registerWorkflow(graph);
        persistWorkflow(graph);
        return graph;
    }

    /**
     * Creates, validates, and hot-registers a linear sequence workflow from an ordered list of step specifications,
     * automatically wiring edges: step_1 -> step_2 -> ... -> __END__.
     * Persists to disk if workflowsDir is configured.
     *
     * @param workflowId   unique workflow identifier
     * @param description  optional human-readable description
     * @param listChannels optional list accumulation channels
     * @param steps        ordered list of step specifications
     * @return validated and registered WorkflowGraph
     */
    public WorkflowGraph createAndRegisterSequence(
            String workflowId,
            String description,
            List<String> listChannels,
            List<SequenceStep> steps
    ) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be null or blank");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("Sequence must contain at least one step");
        }

        List<WorkflowNode> nodes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            SequenceStep step = steps.get(i);
            if (step == null) {
                throw new IllegalArgumentException("Sequence step at index " + i + " must not be null");
            }
            String nodeId = (step.nodeId() != null && !step.nodeId().isBlank())
                    ? step.nodeId().trim()
                    : "step_" + (i + 1);
            if (step.agentId() == null || step.agentId().isBlank()) {
                throw new IllegalArgumentException("Sequence step '" + nodeId + "' must specify an agentId");
            }
            nodes.add(new WorkflowNode(
                    nodeId,
                    step.agentId().trim(),
                    step.taskInstruction(),
                    Boolean.TRUE.equals(step.interruptBefore()),
                    Boolean.TRUE.equals(step.interruptAfter()),
                    step.readChannels(),
                    step.writeChannels()
            ));
        }

        List<WorkflowEdge> edges = new ArrayList<>();
        for (int i = 0; i < nodes.size() - 1; i++) {
            edges.add(new WorkflowEdge(nodes.get(i).nodeId(), nodes.get(i + 1).nodeId()));
        }
        edges.add(new WorkflowEdge(nodes.get(nodes.size() - 1).nodeId(), WorkflowGraph.END_NODE));

        String startNodeId = nodes.get(0).nodeId();
        WorkflowGraph graph = new WorkflowGraph(
                workflowId.trim(),
                description,
                startNodeId,
                nodes,
                edges,
                listChannels != null ? listChannels : Collections.emptyList()
        );

        parserService.validateGraph(graph);
        registerWorkflow(graph);
        persistWorkflow(graph);
        return graph;
    }

    /**
     * Loads, validates, and registers a workflow from a classpath resource path.
     *
     * @param resourcePath classpath resource path (e.g., "workflows/research_pipeline.json")
     * @return validated WorkflowGraph instance
     */
    public WorkflowGraph loadFromResource(String resourcePath) {
        WorkflowGraph graph = parserService.parseFromResource(resourcePath);
        registerWorkflow(graph);
        return graph;
    }

    /**
     * Loads, validates, and registers all workflow JSON files from a filesystem directory.
     *
     * @param directory directory path to scan
     * @return list of newly registered workflows
     */
    public List<WorkflowGraph> loadFromDirectory(Path directory) {
        if (directory == null || !Files.exists(directory) || !Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<WorkflowGraph> loaded = new ArrayList<>();
        try (var stream = Files.walk(directory, 1)) {
            List<Path> jsonFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".json"))
                    .toList();
            for (Path jsonFile : jsonFiles) {
                try {
                    WorkflowGraph graph = parserService.parse(jsonFile);
                    registerWorkflow(graph);
                    loaded.add(graph);
                    log.info("[REGISTRY] Loaded workflow '{}' from file: {}", graph.workflowId(), jsonFile.getFileName());
                } catch (Exception e) {
                    log.warn("[REGISTRY] Could not parse workflow file '{}': {}", jsonFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[REGISTRY] Failed to read workflow directory '{}': {}", directory, e.getMessage());
        }
        return Collections.unmodifiableList(loaded);
    }

    /**
     * Retrieves a registered workflow by identifier (case-insensitive).
     *
     * @param workflowId workflow identifier
     * @return Optional containing the WorkflowGraph if found
     */
    public Optional<WorkflowGraph> getWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registeredWorkflows.get(workflowId.trim().toLowerCase()));
    }

    /**
     * Returns an unmodifiable list of all registered workflow IDs.
     */
    public List<String> listWorkflowIds() {
        return registeredWorkflows.values().stream()
                .map(WorkflowGraph::workflowId)
                .sorted()
                .toList();
    }

    /**
     * Returns an unmodifiable map of registered workflows.
     */
    public Map<String, WorkflowGraph> getRegisteredWorkflows() {
        return Collections.unmodifiableMap(registeredWorkflows);
    }

    /**
     * Generates a comprehensive summary catalog of all registered workflows for LLM inspection.
     */
    public String getCatalogSummary() {
        if (registeredWorkflows.isEmpty()) {
            return "No workflows currently registered in the registry.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== REGISTERED WORKFLOW CATALOG ===\n");
        for (WorkflowGraph graph : registeredWorkflows.values()) {
            sb.append(describeWorkflow(graph)).append("\n\n");
        }
        sb.append("===================================");
        return sb.toString().trim();
    }

    /**
     * Formats a single workflow's nodes, agents, instructions, and routing edges.
     */
    public String describeWorkflow(WorkflowGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("- Workflow ID: ").append(graph.workflowId()).append("\n");
        if (graph.description() != null && !graph.description().isBlank()) {
            sb.append("  Description: ").append(graph.description()).append("\n");
        }
        sb.append("  Start Node: ").append(graph.startNodeId()).append("\n");
        sb.append("  Nodes:\n");
        for (WorkflowNode node : graph.nodes()) {
            sb.append("    * ").append(node.nodeId())
                    .append(" (Agent: ").append(node.agentId()).append(")");
            if (node.taskInstruction() != null && !node.taskInstruction().isBlank()) {
                sb.append(" - ").append(node.taskInstruction());
            }
            if (node.readChannels() != null && !node.readChannels().isEmpty()) {
                sb.append(" [readChannels: ").append(node.readChannels()).append("]");
            }
            if (node.writeChannels() != null && !node.writeChannels().isEmpty()) {
                sb.append(" [writeChannels: ").append(node.writeChannels()).append("]");
            }
            if (node.interruptBefore()) {
                sb.append(" [interruptBefore]");
            }
            if (node.interruptAfter()) {
                sb.append(" [interruptAfter]");
            }
            sb.append("\n");
        }
        sb.append("  Edges:\n");
        for (WorkflowEdge edge : graph.edges()) {
            sb.append("    * ").append(edge.sourceNode()).append(" -> ").append(edge.targetNode());
            if (edge.condition() != null && !edge.condition().isBlank()) {
                sb.append(" [condition: ").append(edge.condition()).append("]");
            }
            sb.append("\n");
        }
        if (!graph.listChannels().isEmpty()) {
            sb.append("  List Channels: ").append(graph.listChannels()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Initializes a {@link WorkflowState} instance with {@link AppendListReducer} registered
     * for standard channels ("notes", "findings", "artifacts", "logs") as well as any custom
     * list channels declared by the workflow definition.
     *
     * @param graph       workflow definition
     * @param initialData optional seed data
     * @return configured WorkflowState
     */
    public WorkflowState createInitialState(WorkflowGraph graph, Map<String, Object> initialData) {
        WorkflowState state = new WorkflowState();

        // 1. Register default list accumulation channels
        Set<String> listChannels = new LinkedHashSet<>(DEFAULT_LIST_CHANNELS);

        // 2. Register custom list channels declared in graph
        if (graph != null && graph.listChannels() != null) {
            for (String ch : graph.listChannels()) {
                if (ch != null && !ch.isBlank()) {
                    listChannels.add(ch.trim());
                }
            }
        }

        for (String channel : listChannels) {
            state.registerReducer(channel, new AppendListReducer<>());
        }

        // 3. Seed initial data
        if (initialData != null && !initialData.isEmpty()) {
            state.applyUpdate(initialData);
        }

        return state;
    }

    /**
     * Executes a registered workflow with an auto-generated thread ID and initial state.
     *
     * @param workflowId   registered workflow ID
     * @param initialState initial key-value state payload
     * @return structured execution outcome
     */
    public WorkflowExecutionResult executeWorkflow(String workflowId, Map<String, Object> initialState) {
        return executeWorkflow(workflowId, UUID.randomUUID().toString(), initialState);
    }

    /**
     * Executes a registered workflow with a designated thread ID and initial state.
     *
     * @param workflowId   registered workflow ID
     * @param threadId     execution run thread ID
     * @param initialState initial key-value state payload
     * @return structured execution outcome
     */
    public WorkflowExecutionResult executeWorkflow(String workflowId, String threadId, Map<String, Object> initialState) {
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be null or blank");
        }
        String key = workflowId.trim().toLowerCase();
        WorkflowGraph graph = registeredWorkflows.get(key);
        if (graph == null) {
            throw new IllegalArgumentException("Workflow not found with ID: '" + workflowId + "'. Registered workflows: " + listWorkflowIds());
        }
        if (agentInvoker == null) {
            throw new IllegalStateException("WorkflowAgentInvoker must be configured to execute workflows.");
        }

        String resolvedThreadId = (threadId != null && !threadId.isBlank()) ? threadId.trim() : UUID.randomUUID().toString();
        log.info("[REGISTRY] Executing workflow '{}' (threadId='{}')", graph.workflowId(), resolvedThreadId);

        WorkflowState state = createInitialState(graph, initialState);
        WorkflowEngine engine = new WorkflowEngine(graph, agentInvoker, state, checkpointer);
        if (this.configResolver != null) {
            engine.setConfigResolver(this.configResolver);
        }

        engine.execute(resolvedThreadId, initialState);

        WorkflowCheckpoint latestCheckpoint = checkpointer.loadLatest(resolvedThreadId).orElse(null);
        WorkflowExecutionStatus status = (latestCheckpoint != null && latestCheckpoint.status() != null)
                ? latestCheckpoint.status()
                : WorkflowExecutionStatus.COMPLETED;

        Set<String> interruptedNodes = (latestCheckpoint != null)
                ? latestCheckpoint.interruptedNodes()
                : Collections.emptySet();

        String message;
        if (status == WorkflowExecutionStatus.SUSPENDED) {
            message = "Workflow execution suspended at node(s) " + interruptedNodes + " in thread '" + resolvedThreadId + "'.";
        } else {
            message = "Workflow '" + graph.workflowId() + "' completed successfully.";
        }

        return new WorkflowExecutionResult(
                resolvedThreadId,
                graph.workflowId(),
                status,
                interruptedNodes,
                latestCheckpoint != null ? latestCheckpoint.stateData() : state.getData(),
                message
        );
    }

    /**
     * Resumes a paused or checkpointed workflow execution by thread ID with optional human-in-the-loop state updates.
     *
     * @param threadId     execution run thread ID to resume
     * @param humanUpdates state modifications injected by the human operator
     * @return structured execution outcome
     */
    public WorkflowExecutionResult resumeWorkflow(String threadId, Map<String, Object> humanUpdates) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be null or blank to resume");
        }
        String resolvedThreadId = threadId.trim();

        WorkflowCheckpoint checkpoint = checkpointer.loadLatest(resolvedThreadId)
                .orElseThrow(() -> new IllegalArgumentException("No checkpoint found for threadId: '" + resolvedThreadId + "'"));

        WorkflowGraph graph = getWorkflow(checkpoint.workflowId())
                .orElseThrow(() -> new IllegalStateException("Workflow '" + checkpoint.workflowId()
                        + "' for thread '" + resolvedThreadId + "' is not registered in the registry"));

        if (agentInvoker == null) {
            throw new IllegalStateException("WorkflowAgentInvoker must be configured to resume workflows.");
        }

        log.info("[REGISTRY] Resuming workflow '{}' (threadId='{}')", graph.workflowId(), resolvedThreadId);

        WorkflowState state = createInitialState(graph, Collections.emptyMap());
        WorkflowEngine engine = new WorkflowEngine(graph, agentInvoker, state, checkpointer);
        if (this.configResolver != null) {
            engine.setConfigResolver(this.configResolver);
        }

        engine.resume(resolvedThreadId, humanUpdates);

        WorkflowCheckpoint latestCheckpoint = checkpointer.loadLatest(resolvedThreadId).orElse(null);
        WorkflowExecutionStatus status = (latestCheckpoint != null && latestCheckpoint.status() != null)
                ? latestCheckpoint.status()
                : WorkflowExecutionStatus.COMPLETED;

        Set<String> interruptedNodes = (latestCheckpoint != null)
                ? latestCheckpoint.interruptedNodes()
                : Collections.emptySet();

        String message;
        if (status == WorkflowExecutionStatus.SUSPENDED) {
            message = "Workflow execution re-suspended at node(s) " + interruptedNodes + " in thread '" + resolvedThreadId + "'.";
        } else {
            message = "Workflow '" + graph.workflowId() + "' resumed and completed successfully.";
        }

        return new WorkflowExecutionResult(
                resolvedThreadId,
                graph.workflowId(),
                status,
                interruptedNodes,
                latestCheckpoint != null ? latestCheckpoint.stateData() : state.getData(),
                message
        );
    }

    public WorkflowAgentInvoker getAgentInvoker() {
        return agentInvoker;
    }

    public void setAgentInvoker(WorkflowAgentInvoker agentInvoker) {
        this.agentInvoker = agentInvoker;
        if (this.configResolver == null && agentInvoker != null) {
            this.configResolver = agentInvoker::getConfig;
        }
    }

    public java.util.function.Function<String, com.agent.config.AgentConfig> getConfigResolver() {
        return configResolver;
    }

    public void setConfigResolver(java.util.function.Function<String, com.agent.config.AgentConfig> configResolver) {
        this.configResolver = configResolver;
    }

    public WorkflowCheckpointer getCheckpointer() {
        return checkpointer;
    }

    public void setCheckpointer(WorkflowCheckpointer checkpointer) {
        this.checkpointer = checkpointer != null ? checkpointer : new InMemoryCheckpointer();
    }

    public WorkflowParserService getParserService() {
        return parserService;
    }

    public Path getWorkflowsDir() {
        return workflowsDir;
    }

    public void setWorkflowsDir(Path workflowsDir) {
        this.workflowsDir = workflowsDir;
    }
}
