package com.agent.orchestration.workflow.engine;

import com.agent.orchestration.workflow.WorkflowToolsFacade;
import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.checkpoint.WorkflowCheckpoint;
import com.agent.orchestration.workflow.checkpoint.WorkflowExecutionStatus;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowExecutionResult;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.services.WorkflowParserService;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import com.agent.orchestration.workflow.state.AppendListReducer;
import com.agent.orchestration.workflow.state.OverwriteReducer;
import com.agent.orchestration.workflow.state.WorkflowState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowSagaTest {

    @Test
    @DisplayName("Should execute compensations in strict LIFO order on downstream failure and persist ROLLED_BACK checkpoint")
    void shouldExecuteCompensationsInLifoOrderOnFailure() {
        // Given 3 nodes: step_1 (comp), step_2 (comp), step_3 (fails)
        WorkflowNode node1 = new WorkflowNode(
                "step_1", "dev_agent", "Do task 1",
                false, false, List.of(), List.of(),
                "comp_agent_1", "Revert task 1"
        );
        WorkflowNode node2 = new WorkflowNode(
                "step_2", "dev_agent", "Do task 2",
                false, false, List.of(), List.of(),
                "comp_agent_2", "Revert task 2"
        );
        WorkflowNode node3 = new WorkflowNode(
                "step_3", "dev_agent", "Do task 3",
                false, false, List.of(), List.of(),
                null, null
        );

        WorkflowGraph graph = new WorkflowGraph(
                "test_saga_lifo",
                "step_1",
                List.of(node1, node2, node3),
                List.of(
                        new WorkflowEdge("step_1", "step_2"),
                        new WorkflowEdge("step_2", "step_3"),
                        new WorkflowEdge("step_3", "__END__")
                ),
                List.of("reverted")
        );

        WorkflowState state = new WorkflowState();
        state.registerReducer("reverted", new AppendListReducer<String>());

        List<String> invocationLog = new ArrayList<>();

        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            invocationLog.add(agentId);
            return switch (agentId) {
                case "dev_agent" -> {
                    if (prompt.contains("Do task 1")) {
                        yield "{\"step1_done\": true}";
                    } else if (prompt.contains("Do task 2")) {
                        yield "{\"step2_done\": true}";
                    } else {
                        throw new RuntimeException("Simulated catastrophic failure in step 3");
                    }
                }
                case "comp_agent_2" -> "{\"reverted\": [\"step_2_reverted\"]}";
                case "comp_agent_1" -> "{\"reverted\": [\"step_1_reverted\"]}";
                default -> throw new IllegalArgumentException("Unknown agent: " + agentId);
            };
        };

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state, checkpointer);

        // When executing and failing at step 3
        assertThatThrownBy(() -> engine.execute("thread-saga-1", Map.of("init", "true")))
                .isInstanceOf(WorkflowSagaRollbackException.class)
                .satisfies(ex -> {
                    WorkflowSagaRollbackException sagaEx = (WorkflowSagaRollbackException) ex;
                    assertThat(sagaEx.getThreadId()).isEqualTo("thread-saga-1");
                    assertThat(sagaEx.getWorkflowId()).isEqualTo("test_saga_lifo");
                    assertThat(sagaEx.getCompensatedNodes()).containsExactly("step_2", "step_1");
                    assertThat(sagaEx.getOriginalCause().getMessage()).contains("Simulated catastrophic failure in step 3");
                });

        // Then verify LIFO execution order: step_2 compensated first, then step_1
        assertThat(invocationLog).containsSubsequence("comp_agent_2", "comp_agent_1");

        // Verify state updates from compensation actions
        List<String> revertedList = state.get("reverted", List.class);
        assertThat(revertedList).containsExactly("step_2_reverted", "step_1_reverted");

        // Verify checkpoint status
        WorkflowCheckpoint latest = checkpointer.loadLatest("thread-saga-1").orElseThrow();
        assertThat(latest.status()).isEqualTo(WorkflowExecutionStatus.ROLLED_BACK);
        assertThat(latest.completedNodes()).containsExactly("step_1", "step_2");
    }

    @Test
    @DisplayName("Should tolerate single compensation failure and continue rolling back remaining upstream nodes")
    void shouldTolerateSingleCompensationFailureAndContinueRollback() {
        WorkflowNode node1 = new WorkflowNode(
                "step_1", "agent_1", "Task 1",
                false, false, List.of(), List.of(),
                "comp_1", "Revert 1"
        );
        WorkflowNode node2 = new WorkflowNode(
                "step_2", "agent_2", "Task 2",
                false, false, List.of(), List.of(),
                "comp_2", "Revert 2"
        );
        WorkflowNode node3 = new WorkflowNode(
                "step_3", "agent_3", "Task 3"
        );

        WorkflowGraph graph = new WorkflowGraph(
                "fault_tolerant_saga",
                "step_1",
                List.of(node1, node2, node3),
                List.of(
                        new WorkflowEdge("step_1", "step_2"),
                        new WorkflowEdge("step_2", "step_3"),
                        new WorkflowEdge("step_3", "__END__")
                ),
                List.of("reverted")
        );

        WorkflowState state = new WorkflowState();
        state.registerReducer("reverted", new AppendListReducer<String>());

        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            return switch (agentId) {
                case "agent_1" -> "{\"step1\": \"done\"}";
                case "agent_2" -> "{\"step2\": \"done\"}";
                case "agent_3" -> throw new IllegalStateException("Task 3 exploded");
                case "comp_2" -> throw new RuntimeException("Compensation for step 2 failed unexpectedly!");
                case "comp_1" -> "{\"reverted\": [\"step_1_successfully_reverted\"]}";
                default -> "{}";
            };
        };

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state, checkpointer);

        assertThatThrownBy(() -> engine.execute("thread-ft-1", Map.of()))
                .isInstanceOf(WorkflowSagaRollbackException.class)
                .satisfies(ex -> {
                    WorkflowSagaRollbackException sagaEx = (WorkflowSagaRollbackException) ex;
                    // comp_2 failed, so only step_1 was recorded as successfully compensated
                    assertThat(sagaEx.getCompensatedNodes()).containsExactly("step_1");
                });

        // Step 1 rollback still succeeded
        List<String> revertedList = state.get("reverted", List.class);
        assertThat(revertedList).containsExactly("step_1_successfully_reverted");

        // Status is still persisted as ROLLED_BACK
        WorkflowCheckpoint cp = checkpointer.loadLatest("thread-ft-1").orElseThrow();
        assertThat(cp.status()).isEqualTo(WorkflowExecutionStatus.ROLLED_BACK);
    }

    @Test
    @DisplayName("Should rethrow directly without Saga rollback when no completed nodes define compensations")
    void shouldRethrowDirectlyWhenNoCompensationsDefined() {
        WorkflowNode node1 = new WorkflowNode("step_1", "agent_1", "Task 1");
        WorkflowNode node2 = new WorkflowNode("step_2", "agent_2", "Task 2");

        WorkflowGraph graph = new WorkflowGraph(
                "no_comp_workflow",
                "step_1",
                List.of(node1, node2),
                List.of(
                        new WorkflowEdge("step_1", "step_2"),
                        new WorkflowEdge("step_2", "__END__")
                )
        );

        WorkflowState state = new WorkflowState();
        WorkflowAgentInvoker invoker = (agentId, prompt) -> {
            if ("agent_1".equals(agentId)) {
                return "{\"step1\": \"ok\"}";
            }
            throw new RuntimeException("Task 2 failed without compensations");
        };

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state, checkpointer);

        assertThatThrownBy(() -> engine.execute("thread-no-comp", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Task 2 failed without compensations")
                .isNotInstanceOf(WorkflowSagaRollbackException.class);

        // Checkpoint from superstep 1 (step_1 completion) remains preserved as RUNNING
        WorkflowCheckpoint cp = checkpointer.loadLatest("thread-no-comp").orElseThrow();
        assertThat(cp.status()).isEqualTo(WorkflowExecutionStatus.RUNNING);
        assertThat(cp.superstep()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should auto-discover default workflows and include compensation metadata in descriptions")
    void shouldAutoDiscoverDefaultWorkflowsAndFormatDescriptions() {
        WorkflowRegistryService registry = new WorkflowRegistryService();
        List<String> ids = registry.listWorkflowIds();

        assertThat(ids).contains(
                "research_pipeline",
                "code_review_pipeline",
                "saga_release_pipeline"
        );

        // Verify code_review_pipeline topology and metadata
        WorkflowGraph reviewGraph = registry.getWorkflow("code_review_pipeline").orElseThrow();
        assertThat(reviewGraph.startNodeId()).isEqualTo("analyze_architecture");
        assertThat(reviewGraph.findNode("implement_fixes").orElseThrow().hasCompensation()).isTrue();
        assertThat(reviewGraph.findNode("verify_and_stage").orElseThrow().interruptBefore()).isTrue();

        // Verify saga_release_pipeline topology and metadata
        WorkflowGraph releaseGraph = registry.getWorkflow("saga_release_pipeline").orElseThrow();
        assertThat(releaseGraph.startNodeId()).isEqualTo("build_artifact");
        assertThat(releaseGraph.findNode("build_artifact").orElseThrow().hasCompensation()).isTrue();
        assertThat(releaseGraph.findNode("provision_staging").orElseThrow().hasCompensation()).isTrue();
        assertThat(releaseGraph.listChannels()).contains("logs", "artifacts");

        // Verify describeWorkflow contains compensation string
        String description = registry.describeWorkflow(releaseGraph);
        assertThat(description).contains("[compensation: agent='developer' instruction='Delete packaged release artifacts from build output directory.']");
        assertThat(description).contains("[compensation: agent='devops' instruction='Deprovision and destroy ephemeral staging environment resources.']");
    }

    @Test
    @DisplayName("Should handle Saga rollback in WorkflowRegistryService and format execution report in WorkflowToolsFacade")
    void shouldHandleSagaRollbackInRegistryAndFacade() {
        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        WorkflowRegistryService registry = new WorkflowRegistryService((WorkflowAgentInvoker) (agentId, prompt) -> {
            if (prompt.contains("SAGA COMPENSATION")) {
                return switch (agentId) {
                    case "devops" -> "{\"logs\": [\"Deprovisioned staging URL\"]}";
                    case "developer" -> "{\"logs\": [\"Deleted build artifact\"]}";
                    default -> "{}";
                };
            }
            return switch (agentId) {
                case "developer" -> "{\"artifacts\": [\"app-v1.0.jar\"], \"status\": \"BUILT\"}";
                case "devops" -> {
                    if (prompt.contains("Provision ephemeral staging")) {
                        yield "{\"environment_url\": \"https://staging.internal.net\", \"status\": \"PROVISIONED\"}";
                    }
                    throw new RuntimeException("Synthetic health check smoke test timeout!");
                }
                default -> "{}";
            };
        }, checkpointer);

        WorkflowToolsFacade facade = new WorkflowToolsFacade(registry);

        // Execute saga_release_pipeline via facade
        String report = facade.executeWorkflow("saga_release_pipeline", "{\"version\": \"1.0.0\"}");

        assertThat(report).contains("=== WORKFLOW SAGA ROLLBACK REPORT ===");
        assertThat(report).contains("Status: ROLLED_BACK");
        assertThat(report).contains("Synthetic health check smoke test timeout!");
        assertThat(report).contains("Deleted build artifact");
        assertThat(report).contains("Deprovisioned staging URL");

        // Verify registry returned ROLLED_BACK result
        WorkflowCheckpoint cp = checkpointer.loadLatest(report.substring(report.indexOf("Thread ID: ") + 11).split("\n")[0].trim()).orElseThrow();
        assertThat(cp.status()).isEqualTo(WorkflowExecutionStatus.ROLLED_BACK);
    }
}
