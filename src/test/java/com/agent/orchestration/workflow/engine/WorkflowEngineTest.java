package com.agent.orchestration.workflow.engine;

import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.model.WorkflowRoutingException;
import com.agent.orchestration.workflow.services.WorkflowParserService;
import com.agent.orchestration.workflow.state.AppendListReducer;
import com.agent.orchestration.workflow.state.OverwriteReducer;
import com.agent.orchestration.workflow.state.WorkflowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowEngineTest {

    @Test
    @DisplayName("Should traverse 3-node sequential research pipeline, inject state, and correctly apply reducers")
    void shouldTraverseThreeNodeSequentialGraphAndReduceState() {
        // 1. Parse sample 3-node research pipeline
        WorkflowParserService parser = new WorkflowParserService();
        WorkflowGraph graph = parser.parseFromResource("workflows/research_pipeline.json");

        // 2. Configure WorkflowState with AppendListReducer on 'notes'
        WorkflowState state = new WorkflowState();
        state.registerReducer("notes", new AppendListReducer<String>());
        state.registerReducer("status", new OverwriteReducer<>());

        // 3. Track prompts delivered to agents to verify context injection
        List<String> receivedPrompts = new ArrayList<>();

        WorkflowAgentInvoker mockInvoker = (agentId, prompt) -> {
            receivedPrompts.add(prompt);
            return switch (agentId.toLowerCase()) {
                case "researcher" -> """
                        ```json
                        {
                          "notes": ["Java 21 introduces Virtual Threads and Record Patterns."],
                          "status": "RESEARCH_COMPLETED"
                        }
                        ```
                        """;
                case "architect" -> """
                        Here is the architectural document:
                        ```json
                        {
                          "notes": ["Designed clean 3-tier reactive pipeline architecture."],
                          "architecture_doc": "Enterprise Java 21 Architecture Document",
                          "status": "ARCHITECTURE_DESIGNED"
                        }
                        ```
                        """;
                case "developer" -> """
                        {
                          "notes": ["Artifact committed to disk at target/docs/java21.md."],
                          "output_file": "target/docs/java21.md",
                          "status": "PIPELINE_COMPLETE"
                        }
                        """;
                default -> throw new IllegalArgumentException("Unexpected agent: " + agentId);
            };
        };

        // 4. Instantiate and execute the engine
        WorkflowEngine engine = new WorkflowEngine(graph, mockInvoker, state);
        WorkflowState finalState = engine.execute(Map.of("project_name", "Operation Phoenix"));

        // 5. Assert sequential traversal and state reduction
        assertThat(finalState.get("project_name")).isEqualTo("Operation Phoenix");
        assertThat(finalState.get("architecture_doc")).isEqualTo("Enterprise Java 21 Architecture Document");
        assertThat(finalState.get("output_file")).isEqualTo("target/docs/java21.md");
        assertThat(finalState.get("status")).isEqualTo("PIPELINE_COMPLETE");

        // Assert AppendListReducer accumulated all 3 steps without data loss
        List<String> notes = finalState.get("notes", List.class);
        assertThat(notes).containsExactly(
                "Java 21 introduces Virtual Threads and Record Patterns.",
                "Designed clean 3-tier reactive pipeline architecture.",
                "Artifact committed to disk at target/docs/java21.md."
        );

        // 6. Assert state injection into downstream agent prompts
        assertThat(receivedPrompts).hasSize(3);
        assertThat(receivedPrompts.get(0)).contains("Operation Phoenix");
        assertThat(receivedPrompts.get(1))
                .contains("Operation Phoenix")
                .contains("Java 21 introduces Virtual Threads and Record Patterns.");
        assertThat(receivedPrompts.get(2))
                .contains("Enterprise Java 21 Architecture Document")
                .contains("Designed clean 3-tier reactive pipeline architecture.");
    }

    @Test
    @DisplayName("Should dynamically branch to remediation node on error and to __END__ on success")
    void shouldDynamicallyBranchBasedOnEvaluatedCondition() {
        // Build branching graph:
        // eval_node -> [condition: status == 'error'] -> remediation_node -> __END__
        // eval_node -> [condition: status == 'success'] -> __END__
        WorkflowGraph branchingGraph = new WorkflowGraph(
                "branching_pipeline",
                "eval_node",
                List.of(
                        new WorkflowNode("eval_node", "architect", "Evaluate health"),
                        new WorkflowNode("remediation_node", "developer", "Apply fix")
                ),
                List.of(
                        new WorkflowEdge("eval_node", "remediation_node", "status == 'error'"),
                        new WorkflowEdge("eval_node", WorkflowGraph.END_NODE, "status == 'success'"),
                        new WorkflowEdge("remediation_node", WorkflowGraph.END_NODE)
                )
        );

        // Case A: Agent returns status = "error" -> routes to remediation_node
        List<String> executedNodesErrorCase = new ArrayList<>();
        WorkflowAgentInvoker errorInvoker = (agentId, prompt) -> {
            executedNodesErrorCase.add(agentId);
            if ("architect".equals(agentId)) {
                return """
                        ```json
                        { "status": "error", "error_code": "RESOURCE_EXHAUSTED" }
                        ```
                        """;
            } else {
                return """
                        ```json
                        { "remediation": "Memory cache flushed", "status": "remediated" }
                        ```
                        """;
            }
        };

        WorkflowEngine engineError = new WorkflowEngine(branchingGraph, errorInvoker);
        WorkflowState stateError = engineError.execute();

        assertThat(executedNodesErrorCase).containsExactly("architect", "developer");
        assertThat(stateError.get("error_code")).isEqualTo("RESOURCE_EXHAUSTED");
        assertThat(stateError.get("remediation")).isEqualTo("Memory cache flushed");

        // Case B: Agent returns status = "success" -> routes directly to __END__
        List<String> executedNodesSuccessCase = new ArrayList<>();
        WorkflowAgentInvoker successInvoker = (agentId, prompt) -> {
            executedNodesSuccessCase.add(agentId);
            return """
                    { "status": "success", "metrics": "All checks nominal" }
                    """;
        };

        WorkflowEngine engineSuccess = new WorkflowEngine(branchingGraph, successInvoker);
        WorkflowState stateSuccess = engineSuccess.execute();

        assertThat(executedNodesSuccessCase).containsExactly("architect");
        assertThat(stateSuccess.get("metrics")).isEqualTo("All checks nominal");
        assertThat(stateSuccess.containsKey("remediation")).isFalse();
    }

    @Test
    @DisplayName("Should correctly take default edge when all conditional edges evaluate to false")
    void shouldTakeDefaultEdgeWhenAllConditionsAreFalse() {
        WorkflowGraph fallbackGraph = new WorkflowGraph(
                "fallback_pipeline",
                "check_node",
                List.of(
                        new WorkflowNode("check_node", "architect", "Verify"),
                        new WorkflowNode("special_node", "developer", "Special Handling"),
                        new WorkflowNode("fallback_node", "devops", "Default Fallback")
                ),
                List.of(
                        new WorkflowEdge("check_node", "special_node", "status == 'SPECIAL'"),
                        new WorkflowEdge("check_node", "fallback_node", null, true), // Default edge
                        new WorkflowEdge("special_node", WorkflowGraph.END_NODE),
                        new WorkflowEdge("fallback_node", WorkflowGraph.END_NODE)
                )
        );

        List<String> executedAgents = new ArrayList<>();
        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            executedAgents.add(agentId);
            if ("architect".equals(agentId)) {
                return """
                        { "status": "NORMAL_UNMATCHED" }
                        """;
            }
            return """
                    { "handled_by": "fallback" }
                    """;
        };

        WorkflowEngine engine = new WorkflowEngine(fallbackGraph, invoker);
        WorkflowState finalState = engine.execute();

        assertThat(executedAgents).containsExactly("architect", "devops");
        assertThat(finalState.get("handled_by")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("Should throw WorkflowRoutingException when no condition evaluates to true and no default edge exists")
    void shouldThrowWorkflowRoutingExceptionWhenNoConditionMatchesAndNoDefault() {
        WorkflowGraph unresolvableGraph = new WorkflowGraph(
                "unresolvable_pipeline",
                "gate_node",
                List.of(
                        new WorkflowNode("gate_node", "architect", "Check"),
                        new WorkflowNode("target_a", "developer", "A"),
                        new WorkflowNode("target_b", "devops", "B")
                ),
                List.of(
                        new WorkflowEdge("gate_node", "target_a", "code == 100"),
                        new WorkflowEdge("gate_node", "target_b", "code == 200"),
                        new WorkflowEdge("target_a", WorkflowGraph.END_NODE),
                        new WorkflowEdge("target_b", WorkflowGraph.END_NODE)
                )
        );

        WorkflowAgentInvoker invoker = (agentId, prompt) -> """
                { "code": 999 }
                """;

        WorkflowEngine engine = new WorkflowEngine(unresolvableGraph, invoker);

        assertThatThrownBy(engine::execute)
                .isInstanceOf(WorkflowRoutingException.class)
                .hasMessageContaining("No condition satisfied for node 'gate_node'");
    }

    @Test
    @DisplayName("Should handle unstructured text response using node output fallback key")
    void shouldHandleUnstructuredTextResponseUsingFallback() {
        WorkflowGraph singleNodeGraph = new WorkflowGraph(
                "fallback_pipeline",
                "node_start",
                List.of(new WorkflowNode("node_start", "developer", "Do work")),
                List.of(new WorkflowEdge("node_start", WorkflowGraph.END_NODE))
        );

        WorkflowAgentInvoker plainTextInvoker = (agentId, prompt) -> "This is non-JSON unstructured plain text response.";

        WorkflowEngine engine = new WorkflowEngine(singleNodeGraph, plainTextInvoker);
        WorkflowState finalState = engine.execute();

        assertThat(finalState.get("node_start_output"))
                .isEqualTo("This is non-JSON unstructured plain text response.");
    }

    @Test
    @DisplayName("Should throw IllegalStateException when an infinite loop or step limit is exceeded")
    void shouldThrowExceptionWhenStepLimitExceeded() {
        // Create circular graph without exit: node_a -> node_b -> node_a
        WorkflowGraph circularGraph = new WorkflowGraph(
                "circular_pipeline",
                "node_a",
                List.of(
                        new WorkflowNode("node_a", "developer", "Task A"),
                        new WorkflowNode("node_b", "developer", "Task B")
                ),
                List.of(
                        new WorkflowEdge("node_a", "node_b"),
                        new WorkflowEdge("node_b", "node_a")
                )
        );

        WorkflowAgentInvoker stubInvoker = (agentId, prompt) -> "{}";

        WorkflowEngine engine = new WorkflowEngine(circularGraph, stubInvoker);

        assertThatThrownBy(engine::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeded maximum step limit");
    }

    @Test
    @DisplayName("Should throw WorkflowRoutingException when intermediate node has no outbound edges")
    void shouldThrowWhenNodeHasNoOutboundEdges() {
        WorkflowGraph brokenGraph = new WorkflowGraph(
                "broken_pipeline",
                "node_isolated",
                List.of(new WorkflowNode("node_isolated", "architect", "Task Isolated")),
                List.of()
        );

        WorkflowAgentInvoker stubInvoker = (agentId, prompt) -> "{}";

        WorkflowEngine engine = new WorkflowEngine(brokenGraph, stubInvoker);

        assertThatThrownBy(engine::execute)
                .isInstanceOf(WorkflowRoutingException.class)
                .hasMessageContaining("has no outbound edges");
    }

    @Test
    @DisplayName("Should execute parallel fan-out nodes concurrently via Virtual Threads and merge state via AppendListReducer")
    void shouldExecuteParallelFanOutNodesConcurrentlyAndMergeState() {
        // Define fan-out graph:
        // node_1 -> node_2a
        // node_1 -> node_2b
        // node_2a -> __END__
        // node_2b -> __END__
        WorkflowGraph fanOutGraph = new WorkflowGraph(
                "fan_out_pipeline",
                "node_1",
                List.of(
                        new WorkflowNode("node_1", "starter", "Initialize pipeline"),
                        new WorkflowNode("node_2a", "worker_a", "Extract Fact A"),
                        new WorkflowNode("node_2b", "worker_b", "Extract Fact B")
                ),
                List.of(
                        new WorkflowEdge("node_1", "node_2a"),
                        new WorkflowEdge("node_1", "node_2b"),
                        new WorkflowEdge("node_2a", WorkflowGraph.END_NODE),
                        new WorkflowEdge("node_2b", WorkflowGraph.END_NODE)
                )
        );

        WorkflowState state = new WorkflowState();
        state.registerReducer("notes", new AppendListReducer<String>());

        List<String> executedThreads = Collections.synchronizedList(new ArrayList<>());

        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            executedThreads.add(Thread.currentThread().toString());
            return switch (agentId) {
                case "starter" -> """
                        { "status": "PIPELINE_INITIALIZED" }
                        """;
                case "worker_a" -> """
                        ```json
                        { "notes": "Fact A" }
                        ```
                        """;
                case "worker_b" -> """
                        ```json
                        { "notes": "Fact B" }
                        ```
                        """;
                default -> "{}";
            };
        };

        WorkflowEngine engine = new WorkflowEngine(fanOutGraph, invoker, state);
        WorkflowState finalState = engine.execute();

        // Verify status from node_1 was preserved
        assertThat(finalState.get("status")).isEqualTo("PIPELINE_INITIALIZED");

        // Verify thread-safe reduction: both Fact A and Fact B must be present without data loss
        @SuppressWarnings("unchecked")
        List<String> notes = finalState.get("notes", List.class);
        assertThat(notes).containsExactlyInAnyOrder("Fact A", "Fact B");

        // Verify 3 agent invocations occurred
        assertThat(executedThreads).hasSize(3);
    }

    @Test
    @DisplayName("Should handle hanging virtual thread safely at synchronization barrier on timeout")
    void shouldHandleHangingVirtualThreadGracefullyOnTimeout() {
        WorkflowGraph hangGraph = new WorkflowGraph(
                "hang_pipeline",
                "hang_node",
                List.of(new WorkflowNode("hang_node", "slow_worker", "Simulate hang")),
                List.of(new WorkflowEdge("hang_node", WorkflowGraph.END_NODE))
        );

        WorkflowAgentInvoker hangingInvoker = (agentId, prompt) -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{ \"status\": \"never_reached\" }";
        };

        // Set barrier timeout to 250 milliseconds
        WorkflowEngine engine = new WorkflowEngine(hangGraph, hangingInvoker, Duration.ofMillis(250));

        assertThatThrownBy(engine::execute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisplayName("Should safely propagate worker agent failure occurring inside virtual thread task")
    void shouldSafelyPropagateWorkerAgentFailure() {
        WorkflowGraph failGraph = new WorkflowGraph(
                "fail_pipeline",
                "fail_node",
                List.of(new WorkflowNode("fail_node", "broken_worker", "Throw error")),
                List.of(new WorkflowEdge("fail_node", WorkflowGraph.END_NODE))
        );

        WorkflowAgentInvoker failingInvoker = (agentId, prompt) -> {
            throw new RuntimeException("Simulated worker agent failure");
        };

        WorkflowEngine engine = new WorkflowEngine(failGraph, failingInvoker);

        assertThatThrownBy(engine::execute)
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated worker agent failure");
    }

    @Test
    @DisplayName("Should support diamond graph with parallel fan-out and downstream fan-in barrier aggregation")
    void shouldSupportDiamondFanOutAndFanInJoin() {
        // Diamond DAG:
        // start_node -> [branch_a, branch_b] -> join_node -> __END__
        WorkflowGraph diamondGraph = new WorkflowGraph(
                "diamond_pipeline",
                "start_node",
                List.of(
                        new WorkflowNode("start_node", "coordinator", "Start"),
                        new WorkflowNode("branch_a", "worker_a", "Process branch A"),
                        new WorkflowNode("branch_b", "worker_b", "Process branch B"),
                        new WorkflowNode("join_node", "aggregator", "Aggregate branches")
                ),
                List.of(
                        new WorkflowEdge("start_node", "branch_a"),
                        new WorkflowEdge("start_node", "branch_b"),
                        new WorkflowEdge("branch_a", "join_node"),
                        new WorkflowEdge("branch_b", "join_node"),
                        new WorkflowEdge("join_node", WorkflowGraph.END_NODE)
                )
        );

        WorkflowState state = new WorkflowState();
        state.registerReducer("metrics", new AppendListReducer<String>());

        List<String> promptReceivedByAggregator = new ArrayList<>();

        WorkflowAgentInvoker invoker = (agentId, prompt) -> switch (agentId) {
            case "coordinator" -> """
                    { "phase": "FAN_OUT" }
                    """;
            case "worker_a" -> """
                    { "metrics": "Metric_Alpha" }
                    """;
            case "worker_b" -> """
                    { "metrics": "Metric_Beta" }
                    """;
            case "aggregator" -> {
                promptReceivedByAggregator.add(prompt);
                yield """
                        { "aggregated": true, "phase": "COMPLETE" }
                        """;
            }
            default -> "{}";
        };

        WorkflowEngine engine = new WorkflowEngine(diamondGraph, invoker, state);
        WorkflowState finalState = engine.execute();

        // Verify parallel reduction in diamond branches
        @SuppressWarnings("unchecked")
        List<String> metrics = finalState.get("metrics", List.class);
        assertThat(metrics).containsExactlyInAnyOrder("Metric_Alpha", "Metric_Beta");

        // Verify aggregator was executed once and received both parallel metrics in its prompt snapshot
        assertThat(promptReceivedByAggregator).hasSize(1);
        assertThat(promptReceivedByAggregator.get(0))
                .contains("Metric_Alpha")
                .contains("Metric_Beta");

        assertThat(finalState.get("aggregated")).isEqualTo(true);
        assertThat(finalState.get("phase")).isEqualTo("COMPLETE");
    }
}
