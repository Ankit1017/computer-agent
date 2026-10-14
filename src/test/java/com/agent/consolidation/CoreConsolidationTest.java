package com.agent.consolidation;

import com.agent.AgentMain;
import com.agent.WorkerAgent;
import com.agent.config.AgentConfig;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.engine.ConditionEvaluator;
import com.agent.orchestration.workflow.engine.WorkflowEngine;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.agent.registry.ToolRegistry;
import com.agent.telemetry.*;
import com.agent.tools.context.ContextToolsFacade;
import com.agent.tools.context.services.BlackboardService;
import com.agent.tools.terminal.security.SecurityInterceptor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CoreConsolidationTest {

    private BlackboardService blackboardService;
    private ContextToolsFacade contextTools;
    private TelemetryToolsFacade telemetryTools;
    private ToolRegistry toolRegistry;
    private ChatLanguageModel mockModel;

    @BeforeEach
    void setUp() {
        SwarmTelemetry.reset();
        blackboardService = new BlackboardService();
        contextTools = new ContextToolsFacade(blackboardService);
        telemetryTools = new TelemetryToolsFacade();
        toolRegistry = new ToolRegistry(null, null, null, null, contextTools, null, telemetryTools);

        mockModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                return Response.from(AiMessage.from("{\"status\": \"SUCCESS\", \"step\": \"done\"}"));
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
                return Response.from(AiMessage.from("{\"status\": \"SUCCESS\", \"step\": \"done\"}"));
            }
        };
    }

    @AfterEach
    void tearDown() {
        if (blackboardService != null) {
            blackboardService.shutdown();
        }
        SwarmTelemetry.reset();
    }

    @Test
    @DisplayName("1. Should record tool.execute spans with parameters and status under SwarmTelemetry")
    void shouldRecordToolExecutionSpansUnderSwarmTelemetry() {
        // Execute tools
        String writeResult = contextTools.writeToBlackboard("test_alpha", "val_123");
        assertThat(writeResult).contains("Wrote key 'test_alpha'");

        String readResult = contextTools.readFromBlackboard("test_alpha");
        assertThat(readResult).isEqualTo("val_123");

        // Verify finished spans in in-memory exporter
        List<SwarmSpan> spans = SwarmTelemetry.getInMemoryExporter().getFinishedSpans();
        assertThat(spans).hasSizeGreaterThanOrEqualTo(2);

        SwarmSpan writeSpan = spans.stream()
                .filter(s -> "tool.execute".equals(s.getName()) && "writeToBlackboard".equals(s.getAttributes().get(TelemetryAttributes.TOOL_NAME)))
                .findFirst()
                .orElse(null);
        assertThat(writeSpan).isNotNull();
        assertThat(writeSpan.getStatus()).isEqualTo(SpanStatus.OK);
        assertThat(String.valueOf(writeSpan.getAttributes().get(TelemetryAttributes.TOOL_ARGUMENTS_JSON)))
                .contains("test_alpha")
                .contains("val_123");

        SwarmSpan readSpan = spans.stream()
                .filter(s -> "tool.execute".equals(s.getName()) && "readFromBlackboard".equals(s.getAttributes().get(TelemetryAttributes.TOOL_NAME)))
                .findFirst()
                .orElse(null);
        assertThat(readSpan).isNotNull();
        assertThat(readSpan.getStatus()).isEqualTo(SpanStatus.OK);

        // Verify TelemetryToolsFacade surfaces recorded metrics
        String summary = telemetryTools.getTelemetrySummary();
        assertThat(summary).contains("=== SWARM TELEMETRY SUMMARY ===")
                .contains("tool.execute");

        String traces = telemetryTools.getRecentTraces(5);
        assertThat(traces).contains("writeToBlackboard")
                .contains("readFromBlackboard");
    }

    @Test
    @DisplayName("2. Should evict sub-agent memory when workflow run completes (COMPLETED) or rolls back (ROLLED_BACK)")
    void shouldEvictMemoryOnWorkflowCompletedAndRolledBack() {
        SubAgentFactory factory = new SubAgentFactory(mockModel, toolRegistry);

        AgentConfig workerConfig = new AgentConfig(
                "worker",
                "Worker Specialist",
                "You are a worker",
                List.of("context")
        );
        factory.createAgent(workerConfig);

        // 1. Successful DAG workflow
        WorkflowGraph successGraph = new WorkflowGraph(
                "success_dag",
                "node_1",
                List.of(
                        new WorkflowNode("node_1", "worker", "Step 1"),
                        new WorkflowNode("node_2", "worker", "Step 2")
                ),
                List.of(
                        new WorkflowEdge("node_1", "node_2"),
                        new WorkflowEdge("node_2", WorkflowGraph.END_NODE)
                )
        );

        String threadIdSuccess = "thread-success-99";
        WorkflowEngine successEngine = new WorkflowEngine(successGraph, factory);
        WorkflowState finalState = successEngine.execute(threadIdSuccess);

        assertThat(finalState.get("status")).isEqualTo("SUCCESS");
        // Verify memories matching threadIdSuccess: were evicted
        assertThat(factory.getActiveMemoryCount()).isEqualTo(0);

        // 2. Failing DAG workflow that triggers Saga rollback
        ChatLanguageModel failingModel = new ChatLanguageModel() {
            private int count = 0;
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                count++;
                if (count == 1) {
                    return Response.from(AiMessage.from("{\"stage1\": \"done\"}"));
                }
                throw new RuntimeException("Simulated catastrophic node failure");
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
                return generate(messages);
            }
        };

        SubAgentFactory failingFactory = new SubAgentFactory(failingModel, toolRegistry);
        failingFactory.createAgent(workerConfig);

        WorkflowGraph sagaGraph = new WorkflowGraph(
                "saga_fail_dag",
                "node_a",
                List.of(
                        new WorkflowNode("node_a", "worker", "Action forward A", false, false, List.of(), List.of(), null, "Action rollback A"),
                        new WorkflowNode("node_b", "worker", "Action forward B", false, false, List.of(), List.of(), null, "Action rollback B")
                ),
                List.of(
                        new WorkflowEdge("node_a", "node_b"),
                        new WorkflowEdge("node_b", WorkflowGraph.END_NODE)
                )
        );

        String threadIdFail = "thread-fail-44";
        WorkflowEngine sagaEngine = new WorkflowEngine(sagaGraph, failingFactory);
        try {
            sagaEngine.execute(threadIdFail);
        } catch (com.agent.orchestration.workflow.engine.WorkflowSagaRollbackException e) {
            assertThat(e.getMessage()).contains("Simulated catastrophic node failure");
        }
        // Verify memories matching threadIdFail: were evicted upon rollback
        assertThat(failingFactory.getActiveMemoryCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("3. Should preserve sub-agent memory when workflow suspends at an interrupt breakpoint (SUSPENDED)")
    void shouldPreserveMemoryWhenWorkflowSuspended() {
        SubAgentFactory factory = new SubAgentFactory(mockModel, toolRegistry);

        AgentConfig reviewerConfig = new AgentConfig(
                "reviewer",
                "Reviewer Specialist",
                "You review things",
                List.of("context")
        );
        factory.createAgent(reviewerConfig);

        // Graph with interruptBefore at node_2
        WorkflowGraph interruptGraph = new WorkflowGraph(
                "hitl_pipeline",
                "node_1",
                List.of(
                        new WorkflowNode("node_1", "reviewer", "Draft proposal"),
                        new WorkflowNode("node_2", "reviewer", "Execute proposal", true, false) // interruptBefore
                ),
                List.of(
                        new WorkflowEdge("node_1", "node_2"),
                        new WorkflowEdge("node_2", WorkflowGraph.END_NODE)
                )
        );

        InMemoryCheckpointer checkpointer = new InMemoryCheckpointer();
        WorkflowEngine engine = new WorkflowEngine(interruptGraph, factory, checkpointer);

        String threadId = "thread-hitl-001";
        WorkflowState suspendedState = engine.execute(threadId);

        assertThat(engine.isSuspended(threadId)).isTrue();

        // SubAgentFactory should NOT have evicted memory for this thread because the workflow is awaiting human resumption
        assertThat(factory.getActiveMemoryCount()).isGreaterThan(0);

        // Now resume the workflow
        WorkflowState resumedState = engine.resume(threadId, Map.of("approved", true));
        assertThat(engine.isSuspended(threadId)).isFalse();

        // After completing the resumed workflow, memory should be evicted
        assertThat(factory.getActiveMemoryCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("4. Should enforce max memory bounding with LRU eviction in SubAgentFactory")
    void shouldEnforceMaxMemoryBoundingWithLruEviction() {
        int cap = 10;
        SubAgentFactory boundedFactory = new SubAgentFactory(mockModel, toolRegistry, null, null, cap);

        AgentConfig dummyConfig = new AgentConfig("test_bot", "Bot", "Desc", List.of("context"));
        WorkerAgent agent = boundedFactory.createAgent(dummyConfig);

        // Populate 15 distinct memory IDs
        for (int i = 1; i <= 15; i++) {
            agent.execute("mem-id-" + i, "Prompt " + i);
        }

        // Must be capped at maxMemoryWindows (10)
        assertThat(boundedFactory.getActiveMemoryCount()).isEqualTo(cap);

        // Oldest memories (1 to 5) should have been evicted
        // Testing explicit evictMemory and evictMemoryByPrefix
        int removedByPrefix = boundedFactory.evictMemoryByPrefix("mem-id-1"); // Matches mem-id-10, 11, 12, 13, 14, 15
        assertThat(removedByPrefix).isGreaterThan(0);
        assertThat(boundedFactory.getActiveMemoryCount()).isLessThan(cap);
    }

    @Test
    @DisplayName("5. Should serialize concurrent SecurityInterceptor prompts on Virtual Threads using fair CONSOLE_LOCK")
    void shouldSerializeConcurrentSecurityPromptsOnVirtualThreads() throws InterruptedException, ExecutionException {
        int threadsCount = 10;
        // Provide sequential "Y\n" responses for each thread
        StringBuilder inputBuilder = new StringBuilder();
        for (int i = 0; i < threadsCount; i++) {
            inputBuilder.append("Y\n");
        }
        InputStream in = new ByteArrayInputStream(inputBuilder.toString().getBytes(StandardCharsets.UTF_8));
        Scanner testScanner = new Scanner(in);
        SecurityInterceptor security = new SecurityInterceptor(testScanner);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<Boolean>> futures = new ArrayList<>();
        AtomicInteger approvedCount = new AtomicInteger(0);

        for (int i = 0; i < threadsCount; i++) {
            final int id = i;
            futures.add(executor.submit(() -> {
                boolean approved = security.requestPermission("run command", "echo 'hello " + id + "'");
                if (approved) {
                    approvedCount.incrementAndGet();
                }
                return approved;
            }));
        }

        for (Future<Boolean> f : futures) {
            assertThat(f.get()).isTrue();
        }
        assertThat(approvedCount.get()).isEqualTo(threadsCount);
        executor.shutdown();
    }

    @Test
    @DisplayName("6. Should load default agents manifest from classpath when local agents.json is absent")
    void shouldLoadAgentsManifestFromClasspathWhenLocalFileAbsent() throws Exception {
        // Load specifying a non-existent local file path
        List<AgentConfig> configs = AgentMain.loadAgentConfigs(Path.of("non_existent_agents_path.json"));

        assertThat(configs).isNotEmpty();
        List<String> agentIds = configs.stream().map(AgentConfig::id).toList();
        assertThat(agentIds).contains("architect", "developer", "devops", "researcher", "gui_tester");
    }

    @Test
    @DisplayName("7. Should emit condition.evaluated span event with missing_state_keys on ConditionEvaluator")
    void shouldRecordConditionEvaluatedSpanEventWithMissingKeys() {
        ConditionEvaluator evaluator = new ConditionEvaluator();
        WorkflowState state = new WorkflowState(Map.of("existing_key", "active"));

        try (SwarmSpan span = SwarmTelemetry.startSpan("test.edge.evaluation")) {
            boolean result = evaluator.evaluate("missing_variable == 'DEPLOYED' && existing_key == 'active'", state);
            assertThat(result).isFalse();
        }

        List<SwarmSpan> finishedSpans = SwarmTelemetry.getInMemoryExporter().getFinishedSpans();
        SwarmSpan edgeSpan = finishedSpans.stream()
                .filter(s -> "test.edge.evaluation".equals(s.getName()))
                .findFirst()
                .orElse(null);

        assertThat(edgeSpan).isNotNull();
        assertThat(edgeSpan.getEvents()).isNotEmpty();

        SpanEvent event = edgeSpan.getEvents().stream()
                .filter(e -> "condition.evaluated".equals(e.name()))
                .findFirst()
                .orElse(null);

        assertThat(event).isNotNull();
        assertThat(event.attributes()).containsKey("missing_state_keys");
        assertThat(String.valueOf(event.attributes().get("missing_state_keys"))).contains("missing_variable");
    }

    @Test
    @DisplayName("8. Should register 'telemetry' category in SubAgentFactory and ToolRegistry")
    void shouldRegisterTelemetryCategoryInSubAgentFactoryAndToolRegistry() {
        assertThat(SubAgentFactory.VALID_TOOL_CATEGORIES).contains("telemetry");
        assertThat(toolRegistry.getAvailableToolNames()).contains("telemetry");

        Object[] tools = toolRegistry.getTools(List.of("telemetry"));
        assertThat(tools).isNotEmpty();
        assertThat(tools[0]).isInstanceOf(TelemetryToolsFacade.class);
    }
}
