package com.agent.telemetry;

import com.agent.WorkerAgent;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.services.OrchestrationService;
import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.engine.WorkflowAgentInvoker;
import com.agent.orchestration.workflow.engine.WorkflowEngine;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.agent.tools.context.services.BlackboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SwarmTelemetryTest {

    @BeforeEach
    void setUp() {
        SwarmTelemetry.reset();
    }

    @Test
    @DisplayName("Should capture complete OpenTelemetry span hierarchy across workflow, superstep, and worker virtual threads")
    void shouldCaptureSpanHierarchyDuringWorkflowExecution() {
        WorkflowNode nodeA = new WorkflowNode("node_a", "agent_1", "Task A");
        WorkflowNode nodeB = new WorkflowNode("node_b", "agent_2", "Task B");

        WorkflowGraph graph = new WorkflowGraph(
                "telemetry_pipeline",
                "node_a",
                List.of(nodeA, nodeB),
                List.of(
                        new WorkflowEdge("node_a", "node_b"),
                        new WorkflowEdge("node_b", "__END__")
                )
        );

        WorkflowAgentInvoker invoker = (agentId, taskPrompt) -> "{\"result\": \"success for " + agentId + "\"}";
        WorkflowState state = new WorkflowState();
        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state, new InMemoryCheckpointer());

        engine.execute("trace-thread-101", Map.of("input_key", "input_value"));

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> finishedSpans = exporter.getFinishedSpans();

        assertThat(finishedSpans).isNotEmpty();

        // 1. Root workflow.run span
        List<SwarmSpan> runSpans = exporter.getSpansByName("workflow.run");
        assertThat(runSpans).hasSize(1);
        SwarmSpan runSpan = runSpans.get(0);
        assertThat(runSpan.getAttribute(TelemetryAttributes.WORKFLOW_ID)).isEqualTo("telemetry_pipeline");
        assertThat(runSpan.getAttribute(TelemetryAttributes.THREAD_ID)).isEqualTo("trace-thread-101");
        assertThat(runSpan.getParentSpanId()).isNull();
        String traceId = runSpan.getTraceId();

        // 2. Superstep spans (2 steps: node_a -> node_b)
        List<SwarmSpan> superstepSpans = exporter.getSpansByName("workflow.superstep");
        assertThat(superstepSpans).hasSize(2);
        for (SwarmSpan stepSpan : superstepSpans) {
            assertThat(stepSpan.getTraceId()).isEqualTo(traceId);
            assertThat(stepSpan.getParentSpanId()).isEqualTo(runSpan.getSpanId());
            assertThat(stepSpan.getAttribute(TelemetryAttributes.SUPERSTEP_INDEX)).isNotNull();
            assertThat(stepSpan.getAttribute(TelemetryAttributes.LLM_CONTEXT_ACTIVE_STATE_JSON)).isNotNull();
        }

        // 3. Agent invoke spans (2 nodes: node_a and node_b)
        List<SwarmSpan> invokeSpans = exporter.getSpansByName("agent.invoke");
        assertThat(invokeSpans).hasSize(2);
        for (SwarmSpan invokeSpan : invokeSpans) {
            assertThat(invokeSpan.getTraceId()).isEqualTo(traceId);
            assertThat(invokeSpan.getAttribute(TelemetryAttributes.AGENT_ID)).isNotNull();
            assertThat(invokeSpan.getAttribute(TelemetryAttributes.MEMORY_ID).toString()).contains("trace-thread-101");
            assertThat(invokeSpan.getAttribute(TelemetryAttributes.LLM_CONTEXT_SYSTEM_PROMPT)).isNotNull();

            // Verify tokens were recorded
            assertThat((Integer) invokeSpan.getAttribute(TelemetryAttributes.LLM_TOKENS_PROMPT)).isGreaterThan(0);
            assertThat((Integer) invokeSpan.getAttribute(TelemetryAttributes.LLM_TOKENS_COMPLETION)).isGreaterThan(0);
            assertThat((Integer) invokeSpan.getAttribute(TelemetryAttributes.LLM_TOKENS_TOTAL)).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Should capture injected core memory and tokens during OrchestrationService routing")
    void shouldCaptureCoreMemoryAndTokensInOrchestrationService() {
        SubAgentFactory mockFactory = mock(SubAgentFactory.class);
        WorkerAgent mockAgent = task -> "Processed response from mock agent";
        when(mockFactory.getAgent("developer")).thenReturn(mockAgent);

        BlackboardService blackboard = new BlackboardService();
        blackboard.writeData("core", "ProjectSecret", "Falcon-9");

        try (OrchestrationService orchestrator = new OrchestrationService(mockFactory, blackboard)) {
            String response = orchestrator.routeTask("developer", "Refactor module", "session-mem-1");
            assertThat(response).isEqualTo("Processed response from mock agent");
        }

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> invokeSpans = exporter.getSpansByName("agent.invoke");
        assertThat(invokeSpans).hasSize(1);

        SwarmSpan span = invokeSpans.get(0);
        assertThat(span.getAttribute(TelemetryAttributes.AGENT_ID)).isEqualTo("developer");
        assertThat(span.getAttribute(TelemetryAttributes.MEMORY_ID)).isEqualTo("session-mem-1");
        assertThat(span.getAttribute(TelemetryAttributes.LLM_CONTEXT_CORE_MEMORY_INJECTED).toString())
                .contains("ProjectSecret: Falcon-9");
        assertThat((Integer) span.getAttribute(TelemetryAttributes.LLM_TOKENS_PROMPT)).isGreaterThan(0);
        assertThat((Integer) span.getAttribute(TelemetryAttributes.LLM_TOKENS_COMPLETION)).isGreaterThan(0);

        blackboard.shutdown();
    }

    @Test
    @DisplayName("Should record workflow.saga.rollback and compensation spans upon forward failure")
    void shouldRecordSagaRollbackSpansOnFailure() {
        WorkflowNode nodeA = new WorkflowNode("step1", "agent_1", "Do forward 1", false, false, List.of(), List.of(), "agent_1", "Rollback forward 1");
        WorkflowNode nodeB = new WorkflowNode("step2", "agent_2", "Do forward 2 that fails");

        WorkflowGraph graph = new WorkflowGraph(
                "failing_saga_pipeline",
                "step1",
                List.of(nodeA, nodeB),
                List.of(
                        new WorkflowEdge("step1", "step2"),
                        new WorkflowEdge("step2", "__END__")
                )
        );

        WorkflowAgentInvoker invoker = (agentId, taskPrompt) -> {
            if (taskPrompt.contains("fails")) {
                throw new RuntimeException("Simulated step2 explosion");
            }
            return "{\"compensated\": true}";
        };

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, new WorkflowState(), new InMemoryCheckpointer());

        try {
            engine.execute("saga-trace-99", Map.of());
        } catch (Exception expected) {
            // Expected rollback exception
        }

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> rollbackSpans = exporter.getSpansByName("workflow.saga.rollback");
        assertThat(rollbackSpans).hasSize(1);

        SwarmSpan rollbackSpan = rollbackSpans.get(0);
        assertThat(rollbackSpan.getAttribute(TelemetryAttributes.WORKFLOW_ID)).isEqualTo("failing_saga_pipeline");
        assertThat(rollbackSpan.getAttribute(TelemetryAttributes.THREAD_ID)).isEqualTo("saga-trace-99");

        // Verify compensation agent invoke span was recorded under rollbackSpan
        List<SwarmSpan> invokeSpans = exporter.getSpansByName("agent.invoke");
        boolean hasCompInvoke = invokeSpans.stream()
                .anyMatch(s -> rollbackSpan.getSpanId().equals(s.getParentSpanId()));
        assertThat(hasCompInvoke).isTrue();
    }

    @Test
    @DisplayName("Should instrument tool execution spans and capture exceptions")
    void shouldInstrumentToolExecutionAndCaptureExceptions() {
        // Successful tool execution
        String toolResult = null;
        try {
            toolResult = SwarmTelemetry.recordToolExecution(
                    "terminal.executeCommand",
                    "{\"command\":\"echo test\"}",
                    () -> "Echo output: test"
            );
        } catch (Exception e) {
            // Unreachable
        }
        assertThat(toolResult).isEqualTo("Echo output: test");

        // Failing tool execution
        try {
            SwarmTelemetry.recordToolExecution(
                    "terminal.executeCommand",
                    "{\"command\":\"rm -rf /\"}",
                    () -> { throw new IllegalStateException("Security breach detected"); }
            );
        } catch (Exception ignored) {
            // Expected
        }

        InMemoryTraceExporter exporter = SwarmTelemetry.getInMemoryExporter();
        List<SwarmSpan> toolSpans = exporter.getSpansByName("tool.execute");
        assertThat(toolSpans).hasSize(2);

        SwarmSpan okSpan = toolSpans.get(0);
        assertThat(okSpan.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(okSpan.getAttribute(TelemetryAttributes.TOOL_NAME)).isEqualTo("terminal.executeCommand");
        assertThat(okSpan.getAttribute(TelemetryAttributes.TOOL_ARGUMENTS_JSON).toString()).contains("echo test");

        SwarmSpan errorSpan = toolSpans.get(1);
        assertThat(errorSpan.getStatus()).isEqualTo(SpanStatus.ERROR);
        assertThat(errorSpan.getAttribute(TelemetryAttributes.ERROR_MESSAGE).toString()).contains("Security breach detected");
    }

    @Test
    @DisplayName("Should export trace logs to JSON Lines file and format telemetry facade diagnostics")
    void shouldExportJsonLinesAndFormatFacade(@TempDir Path tempDir) throws IOException {
        Path jsonlPath = tempDir.resolve("traces.jsonl");
        JsonLinesTraceExporter fileExporter = new JsonLinesTraceExporter(jsonlPath);
        SwarmTelemetry.addExporter(fileExporter);

        try (SwarmSpan span = SwarmTelemetry.startSpan("diagnostic.test")) {
            span.setAttribute(TelemetryAttributes.AGENT_ID, "tester");
            span.recordTokenUsage(150, 50);
        }

        SwarmTelemetry.removeExporter(fileExporter);

        // Verify JSON Lines file
        assertThat(Files.exists(jsonlPath)).isTrue();
        List<String> lines = Files.readAllLines(jsonlPath);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).contains("\"name\":\"diagnostic.test\"");
        assertThat(lines.get(0)).contains("\"agent.id\":\"tester\"");

        // Verify TelemetryToolsFacade
        TelemetryToolsFacade facade = new TelemetryToolsFacade();
        String summary = facade.getTelemetrySummary();
        assertThat(summary).contains("=== SWARM TELEMETRY SUMMARY ===");
        assertThat(summary).contains("Prompt Tokens: 150");
        assertThat(summary).contains("Completion Tokens: 50");
        assertThat(summary).contains("Total Tokens: 200");

        String recent = facade.getRecentTraces(5);
        assertThat(recent).contains("diagnostic.test");
    }
}
