package com.agent.orchestration.workflow.engine;

import com.agent.WorkerAgent;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpoint;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.model.WorkflowRoutingException;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.agent.telemetry.SwarmSpan;
import com.agent.telemetry.SwarmTelemetry;
import com.agent.telemetry.TelemetryAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bulk Synchronous Parallel (BSP) execution engine for declarative workflows,
 * supporting cooperative Human-in-the-Loop (HITL) interrupt breakpoints, state checkpointing,
 * and durable resumption, inspired by Pregel and LangGraph.
 *
 * <p>Executes workflow graphs in discrete "supersteps":
 * <ul>
 *   <li><b>Pre-Execution Interrupts (interruptBefore)</b>: Pauses cleanly before executing designated nodes,
 *       allowing human authorization or parameter modification.</li>
 *   <li><b>Phase A: Concurrent Execution (Virtual Threads)</b>: Active nodes are dispatched
 *       concurrently onto Java 21 Virtual Threads, executing sub-agent tasks with immutable
 *       state snapshots without mutating global state.</li>
 *   <li><b>Phase B: Synchronization Barrier & State Update</b>: The engine blocks at a barrier,
 *       gathers partial updates, and atomically applies them via channel {@link com.agent.orchestration.workflow.state.Reducer}s.</li>
 *   <li><b>Phase C: Planning (Edge Evaluation)</b>: Outbound edges from executed nodes are evaluated
 *       against the newly reduced state using {@link ConditionEvaluator}.</li>
 *   <li><b>Post-Execution Interrupts (interruptAfter)</b>: Pauses cleanly after node completion,
 *       allowing human inspection, approval/rejection, and state injection before downstream branching.</li>
 *   <li><b>Checkpointing & Resumption</b>: Point-in-time snapshots are persisted via {@link WorkflowCheckpointer},
 *       enabling state recovery via {@link #resume(String, Map)} without re-executing completed nodes.</li>
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
    private final WorkflowCheckpointer checkpointer;
    private final ObjectMapper objectMapper;
    private java.util.function.Function<String, com.agent.config.AgentConfig> configResolver;

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker) {
        this(graph, agentInvoker, new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, null);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowCheckpointer checkpointer) {
        this(graph, agentInvoker, new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, checkpointer);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, Duration stepTimeout) {
        this(graph, agentInvoker, new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), stepTimeout, null);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowState state) {
        this(graph, agentInvoker, state, new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, null);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowState state, WorkflowCheckpointer checkpointer) {
        this(graph, agentInvoker, state, new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, checkpointer);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowState state, Duration stepTimeout) {
        this(graph, agentInvoker, state, new StateUpdateParser(), new ConditionEvaluator(), stepTimeout, null);
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowState state, Duration stepTimeout, WorkflowCheckpointer checkpointer) {
        this(graph, agentInvoker, state, new StateUpdateParser(), new ConditionEvaluator(), stepTimeout, checkpointer);
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory) {
        this(graph, adaptAgentFactory(agentFactory), new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, null);
        this.configResolver = agentFactory != null ? agentFactory::getConfig : null;
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory, WorkflowCheckpointer checkpointer) {
        this(graph, adaptAgentFactory(agentFactory), new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, checkpointer);
        this.configResolver = agentFactory != null ? agentFactory::getConfig : null;
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory, Duration stepTimeout) {
        this(graph, adaptAgentFactory(agentFactory), new WorkflowState(), new StateUpdateParser(), new ConditionEvaluator(), stepTimeout, null);
        this.configResolver = agentFactory != null ? agentFactory::getConfig : null;
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory, WorkflowState state) {
        this(graph, adaptAgentFactory(agentFactory), state, new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, null);
        this.configResolver = agentFactory != null ? agentFactory::getConfig : null;
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory, WorkflowState state, WorkflowCheckpointer checkpointer) {
        this(graph, adaptAgentFactory(agentFactory), state, new StateUpdateParser(), new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, checkpointer);
        this.configResolver = agentFactory != null ? agentFactory::getConfig : null;
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser
    ) {
        this(graph, agentInvoker, state, outputParser, new ConditionEvaluator(), DEFAULT_STEP_TIMEOUT, null);
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser,
            ConditionEvaluator conditionEvaluator
    ) {
        this(graph, agentInvoker, state, outputParser, conditionEvaluator, DEFAULT_STEP_TIMEOUT, null);
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser,
            ConditionEvaluator conditionEvaluator,
            Duration stepTimeout
    ) {
        this(graph, agentInvoker, state, outputParser, conditionEvaluator, stepTimeout, null);
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser,
            ConditionEvaluator conditionEvaluator,
            Duration stepTimeout,
            WorkflowCheckpointer checkpointer
    ) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
        this.agentInvoker = Objects.requireNonNull(agentInvoker, "agentInvoker must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator must not be null");
        this.stepTimeout = Objects.requireNonNull(stepTimeout, "stepTimeout must not be null");
        this.checkpointer = checkpointer != null ? checkpointer : new InMemoryCheckpointer();
        this.objectMapper = new ObjectMapper();
        this.configResolver = agentId -> agentInvoker.getConfig(agentId);
    }

    public void setConfigResolver(java.util.function.Function<String, com.agent.config.AgentConfig> configResolver) {
        this.configResolver = configResolver;
    }

    public java.util.function.Function<String, com.agent.config.AgentConfig> getConfigResolver() {
        return configResolver;
    }

    /**
     * Executes the workflow beginning from startNodeId with an auto-generated threadId and empty state.
     *
     * @return final reduced WorkflowState
     */
    public WorkflowState execute() {
        return execute(UUID.randomUUID().toString(), Collections.emptyMap());
    }

    /**
     * Executes the workflow beginning from startNodeId with an auto-generated threadId and initial state.
     *
     * @param initialState initial key-value state payload
     * @return final reduced WorkflowState
     */
    public WorkflowState execute(Map<String, Object> initialState) {
        return execute(UUID.randomUUID().toString(), initialState);
    }

    /**
     * Executes the workflow beginning from startNodeId with a designated threadId.
     *
     * @param threadId unique identifier for the execution run
     * @return final reduced WorkflowState
     */
    public WorkflowState execute(String threadId) {
        return execute(threadId, Collections.emptyMap());
    }

    /**
     * Executes the workflow using the Bulk Synchronous Parallel (BSP) superstep model
     * with checkpointing and cooperative interrupt support.
     *
     * @param threadId     unique identifier for the workflow execution run
     * @param initialState initial key-value state payload
     * @return final reduced WorkflowState
     */
    public WorkflowState execute(String threadId, Map<String, Object> initialState) {
        final String executionThreadId = (threadId != null && !threadId.isBlank())
                ? threadId.trim()
                : UUID.randomUUID().toString();

        if (initialState != null && !initialState.isEmpty()) {
            state.applyUpdate(initialState);
        }

        Set<String> startingActiveNodes = new LinkedHashSet<>();
        startingActiveNodes.add(graph.startNodeId());

        return runSuperstepLoop(executionThreadId, 0, startingActiveNodes, Collections.emptySet(), Collections.emptyList());
    }

    /**
     * Resumes workflow execution from the latest recorded checkpoint matching threadId.
     * Rehydrates state and continues driving active nodes without re-running completed nodes.
     *
     * @param threadId unique workflow run identifier to resume
     * @return final reduced WorkflowState
     */
    public WorkflowState resume(String threadId) {
        return resume(threadId, Collections.emptyMap());
    }

    /**
     * Resumes workflow execution from the latest recorded checkpoint matching threadId,
     * applying human-in-the-loop state updates (e.g. approvals, edits, rejections)
     * and clearing any pre-execution pause blocks.
     *
     * @param threadId          unique workflow run identifier to resume
     * @param humanStateUpdates state modifications injected by the human operator
     * @return final reduced WorkflowState
     */
    public WorkflowState resume(String threadId, Map<String, Object> humanStateUpdates) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId must not be null or blank to resume");
        }

        WorkflowCheckpoint checkpoint = checkpointer.loadLatest(threadId.trim())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot resume workflow. No checkpoint found for threadId: " + threadId
                ));

        log.info("[WORKFLOW] Resuming workflow '{}' for threadId '{}' from superstep {} (status={}) with active nodes: {}",
                graph.workflowId(), threadId, checkpoint.superstep(), checkpoint.status(), checkpoint.activeNodes());

        // 1. Rehydrate state from checkpoint snapshot
        state.restoreState(checkpoint.stateData());

        // 2. Atomically merge human operator state updates via channel reducers
        if (humanStateUpdates != null && !humanStateUpdates.isEmpty()) {
            log.info("[WORKFLOW] Applying human state updates to thread '{}': {}", threadId, humanStateUpdates.keySet());
            state.applyUpdate(humanStateUpdates);
        }

        // 3. Determine starting active nodes and interrupt bypasses
        Set<String> startingActiveNodes = new LinkedHashSet<>(checkpoint.activeNodes());
        Set<String> bypassedInterruptBeforeNodes = new HashSet<>();
        int resumeSuperstepCount = checkpoint.superstep();

        if ("BEFORE".equalsIgnoreCase(checkpoint.interruptType())) {
            // Human authorized the node(s) that paused before execution; bypass interruptBefore on them for this step
            bypassedInterruptBeforeNodes.addAll(checkpoint.interruptedNodes());
            // Since this step was paused before execution, its superstep counter was recorded as pending (checkpoint.superstep()),
            // so we set the initial count to checkpoint.superstep() - 1 so ++superstepCount increments to the step properly
            resumeSuperstepCount = Math.max(0, checkpoint.superstep() - 1);
        } else if ("AFTER".equalsIgnoreCase(checkpoint.interruptType())) {
            // If paused AFTER execution and human modified state, re-evaluate outgoing edges against the updated state
            if (humanStateUpdates != null && !humanStateUpdates.isEmpty() && !checkpoint.interruptedNodes().isEmpty()) {
                List<String> finishedNodes = new ArrayList<>(checkpoint.interruptedNodes());
                startingActiveNodes = evaluateNextActiveNodes(finishedNodes);
                log.info("[WORKFLOW] Re-evaluated outgoing edges after human state injection. New active nodes: {}",
                        startingActiveNodes);
            }
        }

        List<String> completed = checkpoint.completedNodes() != null
                ? checkpoint.completedNodes()
                : Collections.emptyList();

        return runSuperstepLoop(threadId.trim(), resumeSuperstepCount, startingActiveNodes, bypassedInterruptBeforeNodes, completed);
    }

    private WorkflowState runSuperstepLoop(
            String threadId,
            int initialSuperstepCount,
            Set<String> initialActiveNodes,
            Set<String> initialBypassedInterruptBeforeNodes,
            List<String> initialCompletedNodes
    ) {
        Set<String> activeNodes = new LinkedHashSet<>(initialActiveNodes);
        Set<String> bypassedInterruptBeforeNodes = new HashSet<>(initialBypassedInterruptBeforeNodes);
        List<String> completedNodeIds = new ArrayList<>(initialCompletedNodes);
        int superstepCount = initialSuperstepCount;

        log.info("[WORKFLOW] Commencing BSP workflow '{}' (threadId='{}') at superstep {} with active nodes: {}",
                graph.workflowId(), threadId, superstepCount, activeNodes);

        SwarmSpan runSpan = SwarmTelemetry.startSpan("workflow.run");
        runSpan.setAttribute(TelemetryAttributes.WORKFLOW_ID, graph.workflowId());
        runSpan.setAttribute(TelemetryAttributes.THREAD_ID, threadId);

        try {
            while (!activeNodes.isEmpty() && !isTerminated(activeNodes)) {
                // Exclude terminal __END__ node markers from active dispatch
                List<String> executableNodeIds = activeNodes.stream()
                        .filter(id -> !WorkflowGraph.END_NODE.equalsIgnoreCase(id))
                        .toList();

                if (executableNodeIds.isEmpty()) {
                    log.info("[WORKFLOW] No executable nodes remaining in superstep {}. Terminating.", superstepCount);
                    break;
                }

                // =================================================================
                // Pre-Execution Check: interruptBefore Breakpoints
                // =================================================================
                Set<String> interruptBeforeTriggers = new LinkedHashSet<>();
                for (String nodeId : executableNodeIds) {
                    if (bypassedInterruptBeforeNodes.contains(nodeId)) {
                        continue;
                    }
                    WorkflowNode node = graph.findNode(nodeId).orElse(null);
                    if (node != null && node.interruptBefore()) {
                        interruptBeforeTriggers.add(nodeId);
                    }
                }

                if (!interruptBeforeTriggers.isEmpty()) {
                    int suspendedStep = superstepCount + 1;
                    log.info("[WORKFLOW] Pre-execution interrupt triggered for node(s): {} at superstep {} in thread '{}'. Suspending.",
                            interruptBeforeTriggers, suspendedStep, threadId);

                    WorkflowCheckpoint suspendedCheckpoint = new WorkflowCheckpoint(
                            threadId,
                            graph.workflowId(),
                            suspendedStep,
                            activeNodes,
                            state.getData(),
                            System.currentTimeMillis(),
                            WorkflowExecutionStatus.SUSPENDED,
                            interruptBeforeTriggers,
                            "BEFORE",
                            completedNodeIds
                    );
                    checkpointer.save(suspendedCheckpoint);
                    return state;
                }

                // Clear single-step bypasses once past pre-execution checks
                bypassedInterruptBeforeNodes.clear();

                if (++superstepCount > MAX_STEP_LIMIT) {
                    throw new IllegalStateException(
                            "Workflow '" + graph.workflowId() + "' exceeded maximum step limit ("
                                    + MAX_STEP_LIMIT + "). Possible infinite loop or invalid cycle detected."
                    );
                }

                log.info("[WORKFLOW] Superstep {}: Concurrently executing {} node(s): {}",
                        superstepCount, executableNodeIds.size(), executableNodeIds);

                // =================================================================
                // Phase A: Concurrent Execution (Virtual Threads)
                // =================================================================
                Map<String, Object> stateSnapshot = state.getData();
                List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

                try (SwarmSpan superstepSpan = SwarmTelemetry.startSpan("workflow.superstep", runSpan)) {
                    superstepSpan.setAttribute(TelemetryAttributes.WORKFLOW_ID, graph.workflowId());
                    superstepSpan.setAttribute(TelemetryAttributes.THREAD_ID, threadId);
                    superstepSpan.setAttribute(TelemetryAttributes.SUPERSTEP_INDEX, superstepCount);
                    superstepSpan.setAttribute(TelemetryAttributes.LLM_CONTEXT_ACTIVE_STATE_JSON, stateSnapshot.toString());

                    try {
                        for (String nodeId : executableNodeIds) {
                            WorkflowNode node = graph.findNode(nodeId)
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Node '" + nodeId + "' does not exist in workflow '" + graph.workflowId() + "'"
                                    ));

                            final int currentSuperstep = superstepCount;
                            CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                                try (SwarmSpan invokeSpan = SwarmTelemetry.startSpan("agent.invoke", superstepSpan)) {
                                    log.info("[WORKFLOW] [Task] Node '{}' dispatched to agent '{}'", node.nodeId(), node.agentId());
                                    String prompt = formatPrompt(node, stateSnapshot);
                                    String memoryId = threadId + ":" + node.nodeId() + ":step" + currentSuperstep;

                                    invokeSpan.setAttribute(TelemetryAttributes.AGENT_ID, node.agentId());
                                    invokeSpan.setAttribute(TelemetryAttributes.MEMORY_ID, memoryId);
                                    invokeSpan.setAttribute(TelemetryAttributes.WORKFLOW_ID, graph.workflowId());
                                    invokeSpan.setAttribute(TelemetryAttributes.WORKFLOW_NODE_ID, node.nodeId());
                                    invokeSpan.setAttribute(TelemetryAttributes.SUPERSTEP_INDEX, currentSuperstep);
                                    invokeSpan.setAttribute(TelemetryAttributes.THREAD_ID, threadId);
                                    invokeSpan.setAttribute(TelemetryAttributes.LLM_CONTEXT_SYSTEM_PROMPT, prompt);

                                    int promptTokens = SwarmTelemetry.estimateTokens(prompt);
                                    String rawResponse = agentInvoker.invoke(node.agentId(), prompt, memoryId);
                                    int completionTokens = SwarmTelemetry.estimateTokens(rawResponse);
                                    invokeSpan.recordTokenUsage(promptTokens, completionTokens);

                                    return outputParser.parseOutput(rawResponse, node.nodeId() + "_output");
                                }
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

                // Collect and firewall all partial updates from completed tasks
                List<Map<String, Object>> updates = new ArrayList<>();
                for (int i = 0; i < futures.size(); i++) {
                    Map<String, Object> rawUpdate = futures.get(i).join();
                    String executedNodeId = executableNodeIds.get(i);
                    WorkflowNode executedNode = graph.findNode(executedNodeId).orElse(null);
                    Map<String, Object> firewallUpdate = filterWriteUpdate(executedNode, rawUpdate);
                    if (firewallUpdate != null && !firewallUpdate.isEmpty()) {
                        updates.add(firewallUpdate);
                    }
                }

                // Atomically merge updates into WorkflowState via channel reducers
                for (Map<String, Object> update : updates) {
                    state.applyUpdate(update);
                }

                // Mark current superstep nodes as successfully completed forward steps
                completedNodeIds.addAll(executableNodeIds);

            } catch (Throwable failure) {
                Throwable rootCause = failure;
                if (rootCause instanceof ExecutionException ee && ee.getCause() != null) {
                    rootCause = ee.getCause();
                }

                boolean hasCompensatingNodes = completedNodeIds.stream()
                        .map(graph::findNode)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .anyMatch(WorkflowNode::hasCompensation);

                if (!hasCompensatingNodes) {
                    log.warn("[WORKFLOW] Failure during superstep {} with no compensable forward steps. Aborting without Saga rollback.",
                            superstepCount);
                    if (failure instanceof RuntimeException re) {
                        throw re;
                    } else if (failure instanceof Error err) {
                        throw err;
                    } else {
                        throw new IllegalStateException("Workflow superstep " + superstepCount + " failed: " + rootCause.getMessage(), rootCause);
                    }
                }

                log.warn("[WORKFLOW-SAGA] Superstep {} failed for workflow '{}' (threadId='{}'). Initiating LIFO Saga compensation for completed nodes: {}",
                        superstepCount, graph.workflowId(), threadId, completedNodeIds);

                List<String> compensatedNodes = new ArrayList<>();
                List<String> reverseOrder = new ArrayList<>(completedNodeIds);
                Collections.reverse(reverseOrder);

                try (SwarmSpan rollbackSpan = SwarmTelemetry.startSpan("workflow.saga.rollback", runSpan)) {
                    rollbackSpan.setAttribute(TelemetryAttributes.WORKFLOW_ID, graph.workflowId());
                    rollbackSpan.setAttribute(TelemetryAttributes.THREAD_ID, threadId);

                    for (String completedId : reverseOrder) {
                        WorkflowNode node = graph.findNode(completedId).orElse(null);
                        if (node == null || !node.hasCompensation()) {
                            continue;
                        }

                        String compAgentId = (node.compensationAgentId() != null && !node.compensationAgentId().isBlank())
                                ? node.compensationAgentId().trim()
                                : node.agentId();

                        log.info("[WORKFLOW-SAGA] Executing compensation for node '{}' via agent '{}'", node.nodeId(), compAgentId);

                        try (SwarmSpan compSpan = SwarmTelemetry.startSpan("agent.invoke", rollbackSpan)) {
                            compSpan.setAttribute(TelemetryAttributes.AGENT_ID, compAgentId);
                            compSpan.setAttribute(TelemetryAttributes.MEMORY_ID, threadId + ":" + node.nodeId() + ":compensation");
                            compSpan.setAttribute(TelemetryAttributes.WORKFLOW_ID, graph.workflowId());
                            compSpan.setAttribute(TelemetryAttributes.WORKFLOW_NODE_ID, node.nodeId());

                            String compensationPrompt = formatCompensationPrompt(node, state.getData());
                            compSpan.setAttribute(TelemetryAttributes.LLM_CONTEXT_SYSTEM_PROMPT, compensationPrompt);

                            int promptTokens = SwarmTelemetry.estimateTokens(compensationPrompt);
                            String compMemoryId = threadId + ":" + node.nodeId() + ":compensation";
                            String compRawResponse = agentInvoker.invoke(compAgentId, compensationPrompt, compMemoryId);
                            int completionTokens = SwarmTelemetry.estimateTokens(compRawResponse);
                            compSpan.recordTokenUsage(promptTokens, completionTokens);

                            Map<String, Object> rawUpdate = outputParser.parseOutput(compRawResponse, node.nodeId() + "_compensation");
                            Map<String, Object> firewalled = filterWriteUpdate(node, rawUpdate);
                            if (firewalled != null && !firewalled.isEmpty()) {
                                state.applyUpdate(firewalled);
                            }
                            compensatedNodes.add(node.nodeId());
                            log.info("[WORKFLOW-SAGA] Successfully compensated node '{}'. Applied state keys: {}",
                                    node.nodeId(), firewalled != null ? firewalled.keySet() : Collections.emptySet());
                        } catch (Exception e) {
                            log.error("[WORKFLOW-SAGA] Compensation action failed for node '{}' (agent '{}'): {}",
                                    node.nodeId(), compAgentId, e.getMessage(), e);
                        }
                    }
                }

                // Persist ROLLED_BACK checkpoint
                WorkflowCheckpoint rolledBackCheckpoint = new WorkflowCheckpoint(
                        threadId,
                        graph.workflowId(),
                        superstepCount,
                        activeNodes,
                        state.getData(),
                        System.currentTimeMillis(),
                        WorkflowExecutionStatus.ROLLED_BACK,
                        Collections.emptySet(),
                        null,
                        completedNodeIds
                );
                checkpointer.save(rolledBackCheckpoint);
                log.info("[WORKFLOW-SAGA] Saved ROLLED_BACK checkpoint for thread '{}'. Throwing WorkflowSagaRollbackException.", threadId);

                throw new WorkflowSagaRollbackException(
                        threadId,
                        graph.workflowId(),
                        rootCause,
                        compensatedNodes,
                        state
                );
            } finally {
                executor.shutdown();
            }

            // =================================================================
            // Phase C: Planning (Edge Evaluation)
            // =================================================================
            Set<String> nextActiveNodes = evaluateNextActiveNodes(executableNodeIds);
            activeNodes = nextActiveNodes;

            // =================================================================
            // Post-Execution Check: interruptAfter Breakpoints
            // =================================================================
            Set<String> interruptAfterTriggers = new LinkedHashSet<>();
            for (String nodeId : executableNodeIds) {
                WorkflowNode node = graph.findNode(nodeId).orElse(null);
                if (node != null && node.interruptAfter()) {
                    interruptAfterTriggers.add(nodeId);
                }
            }

            boolean isFinal = isTerminated(activeNodes);
            WorkflowExecutionStatus stepStatus = !interruptAfterTriggers.isEmpty()
                    ? WorkflowExecutionStatus.SUSPENDED
                    : (isFinal ? WorkflowExecutionStatus.COMPLETED : WorkflowExecutionStatus.RUNNING);

            String interruptType = !interruptAfterTriggers.isEmpty() ? "AFTER" : null;

            // Checkpoint: Persist state & next active nodes
            WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(
                    threadId,
                    graph.workflowId(),
                    superstepCount,
                    activeNodes,
                    state.getData(),
                    System.currentTimeMillis(),
                    stepStatus,
                    interruptAfterTriggers,
                    interruptType,
                    completedNodeIds
            );
            checkpointer.save(checkpoint);
            log.info("[WORKFLOW] Checkpoint saved for thread '{}' superstep {} (status={}). Next active nodes: {}",
                    threadId, superstepCount, stepStatus, activeNodes);

            if (!interruptAfterTriggers.isEmpty()) {
                log.info("[WORKFLOW] Post-execution interrupt triggered for node(s): {} in thread '{}'. Suspending.",
                        interruptAfterTriggers, threadId);
                return state;
            }
                } // end of try superstepSpan
            } // end of while loop

            log.info("[WORKFLOW] Workflow '{}' (threadId='{}') terminated successfully in {} superstep(s).",
                    graph.workflowId(), threadId, superstepCount);

            return state;
        } catch (Throwable t) {
            runSpan.recordException(t);
            throw t;
        } finally {
            runSpan.close();
        }
    }

    private Set<String> evaluateNextActiveNodes(List<String> nodeIds) {
        Set<String> nextNodes = new LinkedHashSet<>();
        for (String nodeId : nodeIds) {
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
                    if (!hasConditions) {
                        matchedEdges.add(edge);
                    } else {
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
                    WorkflowNode node = graph.findNode(nodeId).orElse(null);
                    if (node != null && node.interruptAfter()) {
                        log.info("[WORKFLOW] Node '{}' has interruptAfter=true; deferring edge routing to human resumption", nodeId);
                    } else {
                        throw new WorkflowRoutingException(
                                "No condition satisfied for node '" + nodeId + "' and no default edge was configured."
                        );
                    }
                }
            }

            for (WorkflowEdge edge : matchedEdges) {
                nextNodes.add(edge.targetNode());
            }
        }
        return nextNodes;
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
     * state snapshot taken at the beginning of the superstep, filtered according
     * to the effective readChannels contract.
     *
     * @param node the current workflow node to execute
     * @param stateData detached state snapshot
     * @return prompt containing active state snapshot, instructions, and format expectations
     */
    protected String formatPrompt(WorkflowNode node, Map<String, Object> stateData) {
        Map<String, Object> scopedState = filterReadState(node, stateData);
        String stateJson;
        try {
            stateJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(scopedState);
        } catch (Exception e) {
            stateJson = scopedState.toString();
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

    /**
     * Formats the prompt dispatched to the compensating agent when rolling back a completed node.
     * Respects read contract scoping via {@link #filterReadState(WorkflowNode, Map)}.
     *
     * @param node the workflow node being compensated
     * @param stateData detached state snapshot
     * @return prompt containing scoped active state snapshot, compensation instructions, and format expectations
     */
    protected String formatCompensationPrompt(WorkflowNode node, Map<String, Object> stateData) {
        Map<String, Object> scopedState = filterReadState(node, stateData);
        String stateJson;
        try {
            stateJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(scopedState);
        } catch (Exception e) {
            stateJson = scopedState.toString();
        }

        String instruction = node.compensationInstruction() != null && !node.compensationInstruction().isBlank()
                ? node.compensationInstruction().trim()
                : "Rollback and revert any actions or mutations performed by node '" + node.nodeId() + "'.";

        return "[[ ACTIVE WORKFLOW STATE ]]\n" +
                stateJson + "\n" +
                "====================================\n\n" +
                "SAGA COMPENSATION / ROLLBACK INSTRUCTION:\n" +
                instruction + "\n\n" +
                "RESPONSE REQUIREMENT:\n" +
                "Provide your response as a valid JSON object (optionally inside a ```json codeblock) " +
                "containing the compensation key-value updates to merge into the workflow state.";
    }

    /**
     * Resolves the effective readChannels for a given workflow node based on precedence:
     * WorkflowNode override -> AgentConfig default -> empty (unrestricted).
     */
    public List<String> resolveEffectiveReadChannels(WorkflowNode node) {
        if (node != null && node.readChannels() != null && !node.readChannels().isEmpty()) {
            return node.readChannels();
        }
        if (node != null && node.agentId() != null) {
            com.agent.config.AgentConfig config = resolveAgentConfig(node.agentId());
            if (config != null && config.readChannels() != null && !config.readChannels().isEmpty()) {
                return config.readChannels();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Resolves the effective writeChannels for a given workflow node based on precedence:
     * WorkflowNode override -> AgentConfig default -> empty (unrestricted).
     */
    public List<String> resolveEffectiveWriteChannels(WorkflowNode node) {
        if (node != null && node.writeChannels() != null && !node.writeChannels().isEmpty()) {
            return node.writeChannels();
        }
        if (node != null && node.agentId() != null) {
            com.agent.config.AgentConfig config = resolveAgentConfig(node.agentId());
            if (config != null && config.writeChannels() != null && !config.writeChannels().isEmpty()) {
                return config.writeChannels();
            }
        }
        return Collections.emptyList();
    }

    private com.agent.config.AgentConfig resolveAgentConfig(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        String normId = agentId.trim().toLowerCase();
        if (configResolver != null) {
            com.agent.config.AgentConfig config = configResolver.apply(normId);
            if (config != null) {
                return config;
            }
        }
        if (agentInvoker != null) {
            return agentInvoker.getConfig(normId);
        }
        return null;
    }

    /**
     * Filters the active state snapshot according to effective readChannels.
     * If readChannels is empty, access is unrestricted.
     */
    public Map<String, Object> filterReadState(WorkflowNode node, Map<String, Object> stateData) {
        if (stateData == null || stateData.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> readChannels = resolveEffectiveReadChannels(node);
        if (readChannels == null || readChannels.isEmpty()) {
            return stateData; // Unrestricted access
        }
        Map<String, Object> scoped = new java.util.LinkedHashMap<>();
        for (String ch : readChannels) {
            if (stateData.containsKey(ch)) {
                scoped.put(ch, stateData.get(ch));
            }
        }
        log.debug("[WORKFLOW-CONTRACT] Node '{}' (agent '{}') read scoped to channels {}: injected keys={}",
                node != null ? node.nodeId() : "unknown",
                node != null ? node.agentId() : "unknown",
                readChannels, scoped.keySet());
        return scoped;
    }

    /**
     * Filters parsed partial updates according to effective writeChannels.
     * Any unauthorized channel write is stripped with a warning logged.
     */
    public Map<String, Object> filterWriteUpdate(WorkflowNode node, Map<String, Object> rawUpdate) {
        if (rawUpdate == null || rawUpdate.isEmpty() || node == null) {
            return rawUpdate != null ? rawUpdate : Collections.emptyMap();
        }
        List<String> writeChannels = resolveEffectiveWriteChannels(node);
        if (writeChannels == null || writeChannels.isEmpty()) {
            return rawUpdate; // Unrestricted write access
        }

        Set<String> allowedChannels = new HashSet<>(writeChannels);
        String fallbackOutputKey = node.nodeId() + "_output";

        Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : rawUpdate.entrySet()) {
            String key = entry.getKey();
            if (allowedChannels.contains(key) || fallbackOutputKey.equals(key)) {
                filtered.put(key, entry.getValue());
            } else {
                log.warn("[WORKFLOW-FIREWALL] Node '{}' (agent '{}') attempted unauthorized write to channel '{}'. Stripping key.",
                        node.nodeId(), node.agentId(), key);
            }
        }
        return filtered;
    }

    public static WorkflowAgentInvoker adaptAgentFactory(SubAgentFactory agentFactory) {
        Objects.requireNonNull(agentFactory, "agentFactory must not be null");
        return new WorkflowAgentInvoker() {
            @Override
            public String invoke(String agentId, String prompt) {
                WorkerAgent agent = agentFactory.getAgent(agentId);
                if (agent == null) {
                    throw new IllegalArgumentException("SubAgentFactory contains no registered agent for id '" + agentId + "'");
                }
                return agent.execute(prompt);
            }

            @Override
            public String invoke(String agentId, String prompt, Object memoryId) {
                WorkerAgent agent = agentFactory.getAgent(agentId);
                if (agent == null) {
                    throw new IllegalArgumentException("SubAgentFactory contains no registered agent for id '" + agentId + "'");
                }
                return agent.execute(memoryId, prompt);
            }

            @Override
            public com.agent.config.AgentConfig getConfig(String agentId) {
                return agentFactory.getConfig(agentId);
            }
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

    public WorkflowCheckpointer getCheckpointer() {
        return checkpointer;
    }

    public WorkflowExecutionStatus getStatus(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return WorkflowExecutionStatus.FAILED;
        }
        return checkpointer.loadLatest(threadId.trim())
                .map(WorkflowCheckpoint::status)
                .orElse(WorkflowExecutionStatus.FAILED);
    }

    public boolean isSuspended(String threadId) {
        return getStatus(threadId) == WorkflowExecutionStatus.SUSPENDED;
    }

    public boolean isCompleted(String threadId) {
        return getStatus(threadId) == WorkflowExecutionStatus.COMPLETED;
    }

    public Optional<WorkflowCheckpoint> getLatestCheckpoint(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return Optional.empty();
        }
        return checkpointer.loadLatest(threadId.trim());
    }
}
