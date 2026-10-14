package com.agent.concurrency;

import com.agent.WorkerAgent;
import com.agent.config.AgentConfig;
import com.agent.factory.EmbeddingModelFactory;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.workflow.checkpoint.InMemoryCheckpointer;
import com.agent.orchestration.workflow.engine.WorkflowAgentInvoker;
import com.agent.orchestration.workflow.engine.WorkflowEngine;
import com.agent.orchestration.workflow.model.WorkflowEdge;
import com.agent.orchestration.workflow.model.WorkflowGraph;
import com.agent.orchestration.workflow.model.WorkflowNode;
import com.agent.orchestration.workflow.state.WorkflowState;
import com.agent.registry.ToolRegistry;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyHardeningTest {

    @Test
    @DisplayName("EmbeddingModelFactory should return a shared singleton instance across callers")
    void shouldReturnSharedEmbeddingModelSingleton() {
        EmbeddingModel model1 = EmbeddingModelFactory.getSharedModel();
        EmbeddingModel model2 = EmbeddingModelFactory.getSharedModel();

        assertThat(model1).isNotNull();
        assertThat(model1).isSameAs(model2);
    }

    @Test
    @DisplayName("WorkerAgent should support isolated execution via memoryId across concurrent threads without memory bleed")
    void shouldIsolateExecutionAcrossConcurrentThreadsUsingMemoryId() throws Exception {
        ChatLanguageModel mockModel = messages -> {
            // Echo the last user message and the total count of messages seen in this memory
            String lastUserMsg = messages.get(messages.size() - 1).text();
            int count = messages.size();
            return dev.langchain4j.model.output.Response.from(
                    dev.langchain4j.data.message.AiMessage.from("MESSAGES_COUNT=" + count + "; ECHO=" + lastUserMsg)
            );
        };

        ToolRegistry emptyRegistry = new ToolRegistry(null, (Object) null, null, null);
        SubAgentFactory factory = new SubAgentFactory(mockModel, emptyRegistry);
        AgentConfig config = new AgentConfig("isolated_worker", "Isolated Worker", "System Instruction", List.of());
        WorkerAgent agent = factory.createAgent(config);

        int threadCount = 10;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        Map<String, String> results = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            final String threadMemId = "session_" + i;
            final String prompt = "Hello from thread " + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread calls execute with its own memoryId
                    String response = agent.execute(threadMemId, prompt);
                    results.put(threadMemId, response);
                } catch (Exception e) {
                    results.put(threadMemId, "ERROR: " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(results).hasSize(threadCount);

        // Every thread should have seen exactly 2 messages in its isolated window (1 system + 1 user)
        for (int i = 0; i < threadCount; i++) {
            String res = results.get("session_" + i);
            assertThat(res).contains("MESSAGES_COUNT=2");
            assertThat(res).contains("ECHO=Hello from thread " + i);
        }
    }

    @Test
    @DisplayName("WorkflowEngine should pass scoped memoryId to WorkflowAgentInvoker during concurrent supersteps")
    void shouldPassScopedMemoryIdDuringWorkflowExecution() {
        WorkflowNode nodeA = new WorkflowNode("node_a", "agent_1", "Task A");
        WorkflowNode nodeB = new WorkflowNode("node_b", "agent_1", "Task B");

        WorkflowGraph graph = new WorkflowGraph(
                "parallel_mem_test",
                "node_a",
                List.of(nodeA, nodeB),
                List.of(
                        new WorkflowEdge("node_a", "node_b"),
                        new WorkflowEdge("node_b", "__END__")
                )
        );

        WorkflowState state = new WorkflowState();
        List<Object> recordedMemoryIds = Collections.synchronizedList(new ArrayList<>());

        WorkflowAgentInvoker invoker = new WorkflowAgentInvoker() {
            @Override
            public String invoke(String agentId, String taskPrompt) {
                return "{\"output\": \"ok\"}";
            }

            @Override
            public String invoke(String agentId, String taskPrompt, Object memoryId) {
                recordedMemoryIds.add(memoryId);
                return "{\"output\": \"ok\"}";
            }
        };

        WorkflowEngine engine = new WorkflowEngine(graph, invoker, state, new InMemoryCheckpointer());
        engine.execute("run-thread-xyz", Map.of());

        assertThat(recordedMemoryIds).hasSize(2);
        // Each node must receive a unique scoped memoryId containing the threadId and nodeId
        assertThat(recordedMemoryIds.get(0).toString()).contains("run-thread-xyz").contains("node_a");
        assertThat(recordedMemoryIds.get(1).toString()).contains("run-thread-xyz").contains("node_b");
    }
}
