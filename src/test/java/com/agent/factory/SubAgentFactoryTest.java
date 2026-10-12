package com.agent.factory;

import com.agent.WorkerAgent;
import com.agent.config.AgentConfig;
import com.agent.registry.ToolRegistry;
import com.agent.tools.GitTools;
import com.agent.tools.MemoryTools;
import com.agent.tools.TerminalTools;
import com.agent.tools.WebTools;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubAgentFactoryTest {

    private ToolRegistry registry;
    private TerminalTools terminalTools;
    private WebTools webTools;
    private MemoryTools memoryTools;
    private GitTools gitTools;

    @BeforeEach
    void setUp() {
        Scanner scanner = new Scanner(System.in);
        terminalTools = new TerminalTools(scanner);
        gitTools = new GitTools(scanner);
        webTools = new WebTools(scanner);
        memoryTools = new MemoryTools(new AllMiniLmL6V2EmbeddingModel(), new InMemoryEmbeddingStore<TextSegment>());

        registry = new ToolRegistry(terminalTools, gitTools, webTools, memoryTools);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when config is null")
    void shouldThrowWhenConfigIsNull() {
        ChatLanguageModel dummyModel = messages -> Response.from(AiMessage.from("OK"));
        SubAgentFactory factory = new SubAgentFactory(dummyModel, registry);

        assertThatThrownBy(() -> factory.createAgent(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AgentConfig must not be null");
    }

    @Test
    @DisplayName("Should create non-null WorkerAgent for valid AgentConfig")
    void shouldCreateWorkerAgent() {
        ChatLanguageModel dummyModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                return Response.from(AiMessage.from("Worker response"));
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
                return Response.from(AiMessage.from("Worker response"));
            }
        };
        SubAgentFactory factory = new SubAgentFactory(dummyModel, registry);

        AgentConfig researcherConfig = new AgentConfig(
                "web_researcher",
                "Web Researcher",
                "You are a researcher.",
                List.of("web", "memory")
        );

        WorkerAgent worker = factory.createAgent(researcherConfig);
        assertThat(worker).isNotNull();

        String result = worker.execute("Test task");
        assertThat(result).isEqualTo("Worker response");
    }

    @Test
    @DisplayName("Should ensure memory isolation and dynamic system message per spawned agent")
    void shouldEnsureMemoryIsolationAndDynamicSystemMessage() {
        List<List<ChatMessage>> recordedInvocations = new ArrayList<>();

        ChatLanguageModel recordingModel = new ChatLanguageModel() {
            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                recordedInvocations.add(new ArrayList<>(messages));
                return Response.from(AiMessage.from("Ack"));
            }

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages, List<dev.langchain4j.agent.tool.ToolSpecification> toolSpecifications) {
                recordedInvocations.add(new ArrayList<>(messages));
                return Response.from(AiMessage.from("Ack"));
            }
        };

        SubAgentFactory factory = new SubAgentFactory(recordingModel, registry);

        AgentConfig researcherConfig = new AgentConfig(
                "web_researcher",
                "Web Researcher",
                "System: Research ONLY",
                List.of("web")
        );

        AgentConfig patcherConfig = new AgentConfig(
                "code_patcher",
                "Code Patcher",
                "System: Patch ONLY",
                List.of("terminal")
        );

        WorkerAgent researcher = factory.createAgent(researcherConfig);
        WorkerAgent patcher = factory.createAgent(patcherConfig);

        // Turn 1 for researcher
        researcher.execute("Task for researcher");
        // Turn 1 for patcher
        patcher.execute("Task for patcher");

        assertThat(recordedInvocations).hasSize(2);

        // Inspect messages sent to model for researcher
        List<ChatMessage> researcherMessages = recordedInvocations.get(0);
        // The first message is the dynamic SystemMessage
        assertThat(researcherMessages).anyMatch(m -> m.text().contains("System: Research ONLY"));
        assertThat(researcherMessages).noneMatch(m -> m.text().contains("System: Patch ONLY"));

        // Inspect messages sent to model for patcher
        List<ChatMessage> patcherMessages = recordedInvocations.get(1);
        // Patcher must NOT contain researcher's prior task or system message
        assertThat(patcherMessages).anyMatch(m -> m.text().contains("System: Patch ONLY"));
        assertThat(patcherMessages).noneMatch(m -> m.text().contains("System: Research ONLY"));
        assertThat(patcherMessages).noneMatch(m -> m.text().contains("Task for researcher"));
    }
}
