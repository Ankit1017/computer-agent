package com.agent.registry;

import com.agent.config.AgentConfig;
import com.agent.tools.ContextTools;
import com.agent.tools.GitTools;
import com.agent.tools.memory.MemoryToolsFacade;
import com.agent.tools.memory.services.LongTermMemoryService;
import com.agent.tools.web.WebToolsFacade;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.agent.tools.terminal.TerminalToolsFacade;
import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.terminal.services.CommandExecutorService;
import com.agent.tools.terminal.services.FileSystemService;
import com.agent.tools.vision.VisionToolsFacade;

import java.io.InputStream;
import java.util.List;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ToolRegistryTest {

    private ToolRegistry registry;
    private TerminalToolsFacade terminalTools;
    private GitTools gitTools;
    private WebToolsFacade webTools;
    private MemoryToolsFacade memoryTools;
    private ContextTools contextTools;
    private VisionToolsFacade visionTools;

    @BeforeEach
    void setUp() {
        Scanner scanner = new Scanner(System.in);
        terminalTools = new TerminalToolsFacade(
                new SecurityInterceptor(scanner),
                new CommandExecutorService(),
                new FileSystemService()
        );
        gitTools = new GitTools(scanner);
        webTools = mock(WebToolsFacade.class);
        memoryTools = new MemoryToolsFacade(new LongTermMemoryService());
        contextTools = new ContextTools();
        visionTools = mock(VisionToolsFacade.class);

        registry = new ToolRegistry(terminalTools, gitTools, webTools, memoryTools, contextTools, visionTools);
    }

    @Test
    @DisplayName("Should successfully resolve tools for web_researcher")
    void shouldResolveWebResearcherTools() {
        Object[] tools = registry.getTools(List.of("web", "memory", "context"));

        assertThat(tools).hasSize(3);
        assertThat(tools[0]).isSameAs(webTools);
        assertThat(tools[1]).isSameAs(memoryTools);
        assertThat(tools[2]).isSameAs(contextTools);
    }

    @Test
    @DisplayName("Should successfully resolve tools for code_patcher")
    void shouldResolveCodePatcherTools() {
        Object[] tools = registry.getTools(List.of("terminal", "context"));

        assertThat(tools).hasSize(2);
        assertThat(tools[0]).isSameAs(terminalTools);
        assertThat(tools[1]).isSameAs(contextTools);
    }

    @Test
    @DisplayName("Should return empty array when requested tools list is empty or null")
    void shouldHandleEmptyOrNullTools() {
        assertThat(registry.getTools(null)).isEmpty();
        assertThat(registry.getTools(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when unknown tool is requested")
    void shouldThrowOnUnknownTool() {
        assertThatThrownBy(() -> registry.getTools(List.of("unknown_tool")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tool requested: 'unknown_tool'");
    }

    @Test
    @DisplayName("Should deserialize agents.json and verify all defined tools resolve in ToolRegistry")
    void shouldDeserializeAgentsJsonAndResolveTools() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getClass().getClassLoader().getResourceAsStream("agents.json");
        assertThat(is).isNotNull();

        List<AgentConfig> configs = mapper.readValue(is, new TypeReference<List<AgentConfig>>() {});
        assertThat(configs).hasSize(5);

        // Verify architect
        AgentConfig architect = configs.stream().filter(c -> "architect".equals(c.id())).findFirst().orElseThrow();
        assertThat(architect.name()).isEqualTo("System Architect");
        assertThat(architect.tools()).containsExactly("terminal", "memory", "context");
        Object[] architectTools = registry.getTools(architect.tools());
        assertThat(architectTools).hasSize(3);

        // Verify developer
        AgentConfig developer = configs.stream().filter(c -> "developer".equals(c.id())).findFirst().orElseThrow();
        assertThat(developer.name()).isEqualTo("Lead Developer");
        assertThat(developer.tools()).containsExactly("terminal", "memory", "context");
        Object[] developerTools = registry.getTools(developer.tools());
        assertThat(developerTools).hasSize(3);

        // Verify devops
        AgentConfig devops = configs.stream().filter(c -> "devops".equals(c.id())).findFirst().orElseThrow();
        assertThat(devops.name()).isEqualTo("DevOps Engineer");
        assertThat(devops.tools()).containsExactly("terminal", "git", "context");
        Object[] devopsTools = registry.getTools(devops.tools());
        assertThat(devopsTools).hasSize(3);

        // Verify gui_tester
        AgentConfig guiTester = configs.stream().filter(c -> "gui_tester".equals(c.id())).findFirst().orElseThrow();
        assertThat(guiTester.name()).isEqualTo("GUI Automation Agent");
        assertThat(guiTester.tools()).containsExactly("vision", "context");
        Object[] guiTools = registry.getTools(guiTester.tools());
        assertThat(guiTools).hasSize(2);

        // Verify researcher
        AgentConfig researcher = configs.stream().filter(c -> "researcher".equals(c.id())).findFirst().orElseThrow();
        assertThat(researcher.name()).isEqualTo("Web Research Specialist");
        assertThat(researcher.tools()).containsExactly("web", "memory", "context");
        Object[] researcherTools = registry.getTools(researcher.tools());
        assertThat(researcherTools).hasSize(3);
    }
}
