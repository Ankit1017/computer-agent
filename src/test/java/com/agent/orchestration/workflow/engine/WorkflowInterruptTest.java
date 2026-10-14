package com.agent.orchestration.workflow.engine;

import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.checkpoint.JsonFileCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpoint;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.state.AppendListReducer;
import com.agent.orchestration.workflow.state.WorkflowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowInterruptTest {

    @Test
    @DisplayName("Should cooperatively suspend at interruptBefore breakpoint and resume to completion upon human approval")
    void shouldSuspendAtInterruptBeforeAndResumeToCompletion() {
        // 3-node pipeline: node_1 -> node_2 (interruptBefore) -> node_3 -> __END__
        WorkflowGraph graph = new WorkflowGraph(
                "hitl_before_pipeline",
                "node_1",
                List.of(
                        new WorkflowNode("node_1", "stage_1", "Draft document"),
                        new WorkflowNode("node_2", "stage_2", "Dangerous deploy step", true, false), // interruptBefore
                        new WorkflowNode("node_3", "stage_3", "Post-deploy notify")
                ),
                List.of(
                        new WorkflowEdge("node_1", "node_2"),
                        new WorkflowEdge("node_2", "node_3"),
                        new WorkflowEdge("node_3", WorkflowGraph.END_NODE)
                )
        );

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        String threadId = "hitl-thread-1";

        AtomicInteger node1Calls = new AtomicInteger(0);
        AtomicInteger node2Calls = new AtomicInteger(0);
        AtomicInteger node3Calls = new AtomicInteger(0);

        WorkflowAgentInvoker invoker = (agentId, prompt) -> switch (agentId) {
            case "stage_1" -> {
                node1Calls.incrementAndGet();
                yield "{ \"draft\": \"Enterprise Document v1\" }";
            }
            case "stage_2" -> {
                node2Calls.incrementAndGet();
                yield "{ \"deployed\": true }";
            }
            case "stage_3" -> {
                node3Calls.incrementAndGet();
                yield "{ \"notified\": true }";
            }
            default -> "{}";
        };

        WorkflowState state = new WorkflowState();
        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state, checkpointer);

        // 1. Initial run: Must execute node_1 and suspend BEFORE node_2
        WorkflowState pausedState = engine.execute(threadId);

        assertThat(pausedState.get("draft")).isEqualTo("Enterprise Document v1");
        assertThat(pausedState.containsKey("deployed")).isFalse();
        assertThat(node1Calls.get()).isEqualTo(1);
        assertThat(node2Calls.get()).isEqualTo(0);
        assertThat(node3Calls.get()).isEqualTo(0);

        // Verify checkpoint reflects SUSPENDED status
        assertThat(engine.isSuspended(threadId)).isTrue();
        assertThat(engine.isCompleted(threadId)).isFalse();

        WorkflowCheckpoint checkpoint = checkpointer.loadLatest(threadId).orElseThrow();
        assertThat(checkpoint.status()).isEqualTo(WorkflowExecutionStatus.SUSPENDED);
        assertThat(checkpoint.interruptType()).isEqualTo("BEFORE");
        assertThat(checkpoint.interruptedNodes()).containsExactly("node_2");
        assertThat(checkpoint.activeNodes()).containsExactly("node_2");

        // 2. Human reviews and calls resume(threadId)
        WorkflowState finalState = engine.resume(threadId);

        // Verify node_1 was NOT re-executed, while node_2 and node_3 executed once
        assertThat(node1Calls.get()).as("node_1 must not re-run").isEqualTo(1);
        assertThat(node2Calls.get()).isEqualTo(1);
        assertThat(node3Calls.get()).isEqualTo(1);

        assertThat(finalState.get("draft")).isEqualTo("Enterprise Document v1");
        assertThat(finalState.get("deployed")).isEqualTo(true);
        assertThat(finalState.get("notified")).isEqualTo(true);

        assertThat(engine.isCompleted(threadId)).isTrue();
        assertThat(engine.isSuspended(threadId)).isFalse();
    }

    @Test
    @DisplayName("Should cooperatively suspend at interruptAfter breakpoint and dynamically route based on human state injection")
    void shouldSuspendAtInterruptAfterAndRouteOnHumanStateModification() {
        // Review pipeline: review_node (interruptAfter) -> approved: publish_node, rejected: rework_node
        WorkflowGraph graph = new WorkflowGraph(
                "hitl_after_pipeline",
                "review_node",
                List.of(
                        new WorkflowNode("review_node", "reviewer", "Review draft", false, true), // interruptAfter
                        new WorkflowNode("publish_node", "publisher", "Publish to prod"),
                        new WorkflowNode("rework_node", "editor", "Revise draft")
                ),
                List.of(
                        new WorkflowEdge("review_node", "publish_node", "approved == true"),
                        new WorkflowEdge("review_node", "rework_node", "approved == false"),
                        new WorkflowEdge("publish_node", WorkflowGraph.END_NODE),
                        new WorkflowEdge("rework_node", WorkflowGraph.END_NODE)
                )
        );

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        String threadId = "hitl-rejection-thread";

        List<String> executedAgents = new ArrayList<>();

        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            executedAgents.add(agentId);
            return switch (agentId) {
                case "reviewer" -> "{ \"review_comments\": \"Contains minor style inconsistencies\" }";
                case "publish_node" -> "{ \"published\": true }";
                case "editor" -> "{ \"rework_completed\": true }";
                default -> "{}";
            };
        };

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, checkpointer);

        // 1. Initial run: Executes review_node and suspends immediately after
        WorkflowState suspendedState = engine.execute(threadId);

        assertThat(executedAgents).containsExactly("reviewer");
        assertThat(suspendedState.get("review_comments", String.class)).contains("Contains minor style inconsistencies");
        assertThat(engine.isSuspended(threadId)).isTrue();

        WorkflowCheckpoint checkpoint = checkpointer.loadLatest(threadId).orElseThrow();
        assertThat(checkpoint.status()).isEqualTo(WorkflowExecutionStatus.SUSPENDED);
        assertThat(checkpoint.interruptType()).isEqualTo("AFTER");
        assertThat(checkpoint.interruptedNodes()).containsExactly("review_node");

        // 2. Human reviews comments, decides to REJECT, and resumes with approved=false
        WorkflowState resumedState = engine.resume(threadId, Map.of("approved", false));

        // Verify routing went to rework_node and NOT publish_node
        assertThat(executedAgents).containsExactly("reviewer", "editor");
        assertThat(resumedState.get("approved")).isEqualTo(false);
        assertThat(resumedState.get("rework_completed")).isEqualTo(true);
        assertThat(resumedState.containsKey("published")).isFalse();

        assertThat(engine.isCompleted(threadId)).isTrue();
    }

    @Test
    @DisplayName("Should persist interrupt state to disk via JsonFileCheckpointer and resume across distinct engine instances")
    void shouldPersistAndResumeInterruptFromDisk(@TempDir Path tempDir) {
        WorkflowGraph graph = new WorkflowGraph(
                "disk_hitl_pipeline",
                "gate_node",
                List.of(
                        new WorkflowNode("gate_node", "auditor", "Audit", true, false), // interruptBefore
                        new WorkflowNode("action_node", "executor", "Execute action")
                ),
                List.of(
                        new WorkflowEdge("gate_node", "action_node"),
                        new WorkflowEdge("action_node", WorkflowGraph.END_NODE)
                )
        );

        JsonFileCheckpointer diskCheckpointer = new JsonFileCheckpointer(tempDir);
        String threadId = "disk-hitl-thread-77";

        WorkflowState state = new WorkflowState();
        state.registerReducer("audit_log", new AppendListReducer<String>());

        AtomicInteger auditorCalls = new AtomicInteger(0);
        AtomicInteger executorCalls = new AtomicInteger(0);

        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            if ("auditor".equals(agentId)) {
                auditorCalls.incrementAndGet();
                return "{ \"audit_log\": \"Audit passed by AI\" }";
            }
            executorCalls.incrementAndGet();
            return "{ \"audit_log\": \"Action completed safely\" }";
        };

        WorkflowEngine engine1 = new WorkflowEngine(graph, invoker, state, diskCheckpointer);

        // 1. Initial run: Suspends immediately BEFORE gate_node (start node has interruptBefore=true)
        engine1.execute(threadId, Map.of("audit_log", "Initial Request Submitted"));

        assertThat(auditorCalls.get()).isEqualTo(0);
        assertThat(executorCalls.get()).isEqualTo(0);
        assertThat(engine1.isSuspended(threadId)).isTrue();

        // 2. Simulate restarting process with new WorkflowEngine reading from disk
        JsonFileCheckpointer newDiskCheckpointer = new JsonFileCheckpointer(tempDir);
        WorkflowState newState = new WorkflowState();
        newState.registerReducer("audit_log", new AppendListReducer<String>());

        WorkflowEngine engine2 = new WorkflowEngine(graph, invoker, newState, newDiskCheckpointer);

        // Resume with human authorization injected
        WorkflowState finalState = engine2.resume(threadId, Map.of(
                "human_operator", "SecOps_Admin",
                "audit_log", "Human override authorized"
        ));

        assertThat(auditorCalls.get()).isEqualTo(1);
        assertThat(executorCalls.get()).isEqualTo(1);
        assertThat(finalState.get("human_operator")).isEqualTo("SecOps_Admin");

        @SuppressWarnings("unchecked")
        List<String> auditLogs = finalState.get("audit_log", List.class);
        assertThat(auditLogs).containsExactly(
                "Initial Request Submitted",
                "Human override authorized",
                "Audit passed by AI",
                "Action completed safely"
        );

        assertThat(engine2.isCompleted(threadId)).isTrue();
    }
}
