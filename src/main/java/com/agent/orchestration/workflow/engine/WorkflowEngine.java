package com.agent.orchestration.workflow.engine;

import com.agent.WorkerAgent;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.model.WorkflowRoutingException;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bulk Synchronous Parallel (BSP) execution engine for declarative workflows,
 * inspired by Pregel and LangGraph.
 *
 * <p>Executes workflow graphs in discrete "supersteps":
 * <ul>
 *   <li><b>Phase A: Concurrent Execution (Virtual Threads)</b>: Active nodes are dispatched
 *       concurrently onto Java 21 Virtual Threads, executing sub-agent tasks with immutable
 *       state snapshots without mutating global state.</li>
 *   <li><b>Phase B: Synchronization Barrier & State Update</b>: The engine blocks at a barrier,
 *       gathers partial updates, and atomically applies them via channel {@link com.agent.orchestration.workflow.state.Reducer}s.</li>
 *   <li><b>Phase C: Planning (Edge Evaluation)</b>: Outbound edges from executed nodes are evaluated
 *       against the newly reduced state using {@link ConditionEvaluator}, collecting active nodes for
 *       the subsequent superstep until termination.</li>
 * </ul>
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final int MAX_STEP_LIMIT = 100;
    public static final Duration DEFAULT_STEP_TIMEOUT = Duration.ofSeconds(60);

    private final WorkflowGraph graph;
    private final WorkflowState state;
    private final WorkflowAgentInvoker agentInvoker;
    private final StateUpdateParser outputParser;
    private final ConditionEvaluator conditionEvaluator;
    private final Duration stepTimeout;
    private final ObjectMapper objectMapper;

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker) {
        this(graph, agentInvoker, new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, Duration stepTimeout) {
        this(graph, agentInvoker, new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), stepTimeout);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowState state) {
        this(graph, agentInvoker, state, new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowState state, Duration stepTimeout) {
        this(graph, agentInvoker, state, new StateUpdateParser(), new ConditionEvaluator(), stepTimeout);
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory) {
        this(graph, adaptAgentFactory(agentFactory), new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT);
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory, Duration stepTimeout) {
        this(graph, adaptAgentFactory(agentFactory), new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), stepTimeout);
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory, WorkflowState state) {
        this(graph, adaptAgentFactory(agentFactory), state, new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT);
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser
    ) {
        this(graph, agentInvoker, state, outputParser, new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT);
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser,
            ConditionEvaluator conditionEvaluator
    ) {
        this(graph, agentInvoker, state, outputParser, conditionEvaluator, DEFAULT_STEP_TIMEOUT);
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser,
            ConditionEvaluator conditionEvaluator,
            Duration stepTimeout
    ) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
        this.agentInvoker = Objects.requireNonNull(agentInvoker, "agentInvoker must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator must not be null");
        this.stepTimeout = Objects.requireNonNull(stepTimeout, "stepTimeout must not be null");
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Executes the workflow beginning from startNodeId with an empty initial state.
     *
     * @return final reduced WorkflowState
     */
    public WorkflowState execute() {
        return execute(Collections.emptyMap());
    }

    /**
     * Executes the workflow using the Bulk Synchronous Parallel (BSP) superstep model.
     * In each superstep:
     * 1. Phase A: Spawns concurrent virtual threads for all active nodes.
     * 2. Phase B: Synchronizes at the barrier and merges partial updates atomically.
     * 3. Phase C: Evaluates outgoing edges against the updated state to plan the next superstep.
     *
     * @param initialState initial key-value state payload
     * @return final reduced WorkflowState
     */
    public WorkflowState execute(Map<String, Object> initialState) {
        if (initialState != null && !initialState.isEmpty()) {
            state.applyUpdate(initialState);
        }

        Set<String> activeNodes = new LinkedHashSet<>();
        activeNodes.add(graph.startNodeId());
        int superstepCount = 0;

        log.info("[WORKFLOW] Commencing BSP workflow '{}' at start node '{}'", graph.workflowId(), graph.startNodeId());

        while (!activeNodes.isEmpty() && !isTerminated(activeNodes)) {
            if (++superstepCount > MAX_STEP_LIMIT) {
                throw new IllegalStateException(
                        "Workflow '" + graph.workflowId() + "' exceeded maximum step limit ("
                                + MAX_STEP_LIMIT + "). Possible infinite loop or invalid cycle detected."
                );
            }

            // Exclude terminal __END__ node markers from active dispatch
            List<String> executableNodeIds = activeNodes.stream()
                    .filter(id -> !WorkflowGraph.END_NODE.equalsIgnoreCase(id))
                    .toList();

            if (executableNodeIds.isEmpty()) {
                log.info("[WORKFLOW] No executable nodes remaining in superstep {}. Terminating.", superstepCount);
                break;
            }

            log.info("[WORKFLOW] Superstep {}: Concurrently executing {} node(s): {}",
                    superstepCount, executableNodeIds.size(), executableNodeIds);

            // =================================================================
            // Phase A: Concurrent Execution (Virtual Threads)
            // =================================================================
            Map<String, Object> stateSnapshot = state.getData();
            List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

            try {
                for (String nodeId : executableNodeIds) {
                    WorkflowNode node = graph.findNode(nodeId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Node '" + nodeId + "' does not exist in workflow '" + graph.workflowId() + "'"
                            ));

                    CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                        log.info("[WORKFLOW] [Task] Node '{}' dispatched to agent '{}'", node.nodeId(), node.agentId());
                        String prompt = formatPrompt(node, stateSnapshot);
                        String rawResponse = agentInvoker.invoke(node.agentId(), prompt);
                        return outputParser.parseOutput(rawResponse, node.nodeId() + "_output");
                    }, executor);

                    futures.add(future);
                }

                // =================================================================
                // Phase B: Synchronization Barrier & State Update
                // =================================================================
                CompletableFuture<Void> barrier = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                try {
                    barrier.get(stepTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    executor.shutdownNow();
                    futures.forEach(f -> f.cancel(true));
                    throw new IllegalStateException(
                            "Workflow superstep " + superstepCount + " timed out after "
                                    + stepTimeout + " waiting for active nodes: " + executableNodeIds, e
                    );
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new IllegalStateException(
                            "Task execution failed during superstep " + superstepCount + ": " + cause.getMessage(), cause
                    );
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Workflow superstep " + superstepCount + " was interrupted", e);
                }

                // Collect all partial updates from completed tasks
                List<Map<String, Object>> updates = new ArrayList<>();
                for (CompletableFuture<Map<String, Object>> future : futures) {
                    Map<String, Object> update = future.join();
                    if (update != null && !update.isEmpty()) {
                        updates.add(update);
                    }
                }

                // Atomically merge updates into WorkflowState via channel reducers
                for (Map<String, Object> update : updates) {
                    state.applyUpdate(update);
                }

            } finally {
                executor.shutdown();
            }

            // =================================================================
            // Phase C: Planning (Edge Evaluation)
            // =================================================================
            Set<String> nextActiveNodes = new LinkedHashSet<>();

            for (String nodeId : executableNodeIds) {
                List<WorkflowEdge> outboundEdges = graph.getOutboundEdges(nodeId);
                if (outboundEdges.isEmpty()) {
                    throw new WorkflowRoutingException(
                            "Node '" + nodeId + "' has no outbound edges and did not transition to '"
                                    + WorkflowGraph.END_NODE + "'"
                    );
                }

                boolean hasConditions = outboundEdges.stream()
                        .anyMatch(e -> e.condition() != null && !e.condition().isBlank());

                List<WorkflowEdge> matchedEdges = new ArrayList<>();
                List<WorkflowEdge> defaultEdges = new ArrayList<>();

                for (WorkflowEdge edge : outboundEdges) {
                    if (edge.isDefault()) {
                        defaultEdges.add(edge);
                    } else if (edge.condition() != null && !edge.condition().isBlank()) {
                        if (conditionEvaluator.evaluate(edge.condition(), state)) {
                            matchedEdges.add(edge);
                            log.info("[WORKFLOW] Condition '{}' met for node '{}'. Routing to '{}'",
                                    edge.condition(), nodeId, edge.targetNode());
                        }
                    } else {
                        // Unconditional edge
                        if (!hasConditions) {
                            matchedEdges.add(edge);
                        } else {
                            // Acts as fallback candidate when conditional edges exist on this node
                            defaultEdges.add(edge);
                        }
                    }
                }

                if (matchedEdges.isEmpty()) {
                    if (!defaultEdges.isEmpty()) {
                        matchedEdges.addAll(defaultEdges);
                        log.info("[WORKFLOW] No condition satisfied for node '{}'. Routing to {} default edge(s)",
                                nodeId, defaultEdges.size());
                    } else {
                        throw new WorkflowRoutingException(
                                "No condition satisfied for node '" + nodeId + "' and no default edge was configured."
                        );
                    }
                }

                for (WorkflowEdge edge : matchedEdges) {
                    nextActiveNodes.add(edge.targetNode());
                }
            }

            activeNodes = nextActiveNodes;
            log.info("[WORKFLOW] Superstep {} completed. Next active nodes: {}", superstepCount, activeNodes);
        }

        log.info("[WORKFLOW] Workflow '{}' terminated successfully in {} superstep(s).",
                graph.workflowId(), superstepCount);

        return state;
    }

    private boolean isTerminated(Set<String> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return true;
        }
        return nodes.stream().allMatch(id -> WorkflowGraph.END_NODE.equalsIgnoreCase(id));
    }

    /**
     * Formats the prompt delivered to the executing sub-agent, injecting
     * the active workflow state context as JSON.
     *
     * @param node the current workflow node to execute
     * @return prompt containing active state, instructions, and format expectations
     */
    protected String formatPrompt(WorkflowNode node) {
        return formatPrompt(node, state.getData());
    }

    /**
     * Formats the prompt delivered to the executing sub-agent using an immutable
     * state snapshot taken at the beginning of the superstep.
     *
     * @param node the current workflow node to execute
     * @param stateData detached state snapshot
     * @return prompt containing active state snapshot, instructions, and format expectations
     */
    protected String formatPrompt(WorkflowNode node, Map<String, Object> stateData) {
        String stateJson;
        try {
            stateJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(stateData);
        } catch (Exception e) {
            stateJson = stateData.toString();
        }

        return "[[ ACTIVE WORKFLOW STATE ]]\n" +
                stateJson + "\n" +
                "====================================\n\n" +
                "TASK INSTRUCTION:\n" +
                node.taskInstruction() + "\n\n" +
                "RESPONSE REQUIREMENT:\n" +
                "Provide your response as a valid JSON object (optionally inside a ```json codeblock) " +
                "containing the key-value updates to merge into the workflow state.";
    }

    private static WorkflowAgentInvoker adaptAgentFactory(SubAgentFactory agentFactory) {
        Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        return (agentId, prompt) -> {
            WorkerAgent agent = agentFactory.getAgent(agentId);
            if (agent == null) {
                throw new IllegalArgumentException("SubAgentFactory contains no registered agent for id '" + agentId + "'");
            }
            return agent.execute(prompt);
        };
    }

    public WorkflowGraph getGraph() {
        return graph;
    }

    public WorkflowState getState() {
        return state;
    }

    public ConditionEvaluator getConditionEvaluator() {
        return conditionEvaluator;
    }

    public Duration getStepTimeout() {
        return stepTimeout;
    }
}
