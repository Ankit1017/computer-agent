package com.agent.orchestration.workflow.engine;

import com.agent.WorkerAgent;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic sequential execution engine for declarative workflows,
 * inspired by LangGraph and Pregel.
 *
 * <p>Traverses nodes in a {@link WorkflowGraph}, dispatches tasks to specialized sub-agents
 * while injecting active state context, parses partial updates, applies them through
 * configured channel reducers in {@link WorkflowState}, and traverses static transitions
 * until the terminal marker {@code __END__} is reached.
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final int MAX_STEP_LIMIT = 100;

    private final WorkflowGraph graph;
    private final WorkflowState state;
    private final WorkflowAgentInvoker agentInvoker;
    private final StateUpdateParser outputParser;
    private final ObjectMapper objectMapper;

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker) {
        this(graph, agentInvoker, new WorkflowState(), new StateUpdateParser());
    }

    public WorkflowEngine(WorkflowGraph graph, WorkflowAgentInvoker agentInvoker, WorkflowState state) {
        this(graph, agentInvoker, state, new StateUpdateParser());
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory) {
        this(graph, adaptAgentFactory(agentFactory), new WorkflowState(), new StateUpdateParser());
    }

    public WorkflowEngine(WorkflowGraph graph, SubAgentFactory agentFactory, WorkflowState state) {
        this(graph, adaptAgentFactory(agentFactory), state, new StateUpdateParser());
    }

    public WorkflowEngine(
            WorkflowGraph graph,
            WorkflowAgentInvoker agentInvoker,
            WorkflowState state,
            StateUpdateParser outputParser
    ) {
        this.graph = Objects.requireNonNull(graph, "graph must not be null");
        this.agentInvoker = Objects.requireNonNull(agentInvoker, "agentInvoker must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
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
     * Executes the workflow sequentially from startNodeId to __END__.
     *
     * @param initialState initial key-value state payload
     * @return final reduced WorkflowState
     */
    public WorkflowState execute(Map<String, Object> initialState) {
        if (initialState != null && !initialState.isEmpty()) {
            state.applyUpdate(initialState);
        }

        String currentNodeId = graph.startNodeId();
        int stepCount = 0;

        log.info("[WORKFLOW] Commencing workflow '{}' at start node '{}'", graph.workflowId(), currentNodeId);

        while (!WorkflowGraph.END_NODE.equalsIgnoreCase(currentNodeId)) {
            if (++stepCount > MAX_STEP_LIMIT) {
                throw new IllegalStateException(
                        "Workflow '" + graph.workflowId() + "' exceeded maximum step limit ("
                                + MAX_STEP_LIMIT + "). Possible infinite loop or invalid cycle detected."
                );
            }

            final String targetNodeId = currentNodeId;
            WorkflowNode node = graph.findNode(targetNodeId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Node '" + targetNodeId + "' does not exist in workflow '" + graph.workflowId() + "'"
                    ));

            log.info("[WORKFLOW] Step {}: Executing node '{}' with agent '{}'", stepCount, node.nodeId(), node.agentId());

            // 1. Format agent prompt with active WorkflowState context
            String prompt = formatPrompt(node);

            // 2. Invoke the designated agent
            String rawResponse = agentInvoker.invoke(node.agentId(), prompt);

            // 3. Parse partial state update from agent response
            Map<String, Object> partialUpdate = outputParser.parseOutput(rawResponse, node.nodeId() + "_output");

            // 4. Apply partial update to WorkflowState via channel reducers
            state.applyUpdate(partialUpdate);

            // 5. Look up the outgoing edge for the current node
            List<WorkflowEdge> outboundEdges = graph.getOutboundEdges(node.nodeId());
            if (outboundEdges.isEmpty()) {
                throw new IllegalStateException(
                        "Node '" + node.nodeId() + "' has no outbound edges and did not transition to '"
                                + WorkflowGraph.END_NODE + "'"
                );
            }

            // In sequential execution, traverse the single outbound transition
            WorkflowEdge nextEdge = outboundEdges.get(0);
            currentNodeId = nextEdge.targetNode();

            log.info("[WORKFLOW] Step {} completed. Transitioning to '{}'", stepCount, currentNodeId);
        }

        log.info("[WORKFLOW] Workflow '{}' terminated successfully at '{}' in {} steps.",
                graph.workflowId(), currentNodeId, stepCount);

        return state;
    }

    /**
     * Formats the prompt delivered to the executing sub-agent, injecting
     * the active workflow state context as JSON.
     *
     * @param node the current workflow node to execute
     * @return prompt containing active state, instructions, and format expectations
     */
    protected String formatPrompt(WorkflowNode node) {
        String stateJson;
        try {
            stateJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state.getData());
        } catch (Exception e) {
            stateJson = state.getData().toString();
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
}
