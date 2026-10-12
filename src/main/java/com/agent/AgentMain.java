package com.agent;

import com.agent.config.AgentConfig;
import com.agent.factory.OllamaModelFactory;
import com.agent.factory.SubAgentFactory;
import com.agent.registry.ToolRegistry;
import com.agent.tools.GitTools;
import com.agent.tools.MemoryTools;
import com.agent.tools.TerminalTools;
import com.agent.tools.WebTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;

/**
 * Phase 7, Milestone 7.1.2: The SubAgentFactory Execution Verification.
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Agent - Milestone 7.1.2 SubAgentFactory Verification");

        try {
            // 1. Shared Scanner for HITL tool security confirmation
            Scanner scanner = new Scanner(System.in);

            // 2. Initialize Local Vector Store & In-Process ONNX Embedding Model
            EmbeddingModel embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            EmbeddingStore<TextSegment> embeddingStore;
            try {
                embeddingStore = ChromaEmbeddingStore.builder()
                        .baseUrl("http://localhost:8000")
                        .collectionName("agent_memory")
                        .timeout(Duration.ofSeconds(2))
                        .build();
            } catch (Exception e) {
                log.warn("ChromaDB not reachable at http://localhost:8000, using in-memory store for verification");
                embeddingStore = new InMemoryEmbeddingStore<>();
            }

            // 3. Instantiate Tools
            TerminalTools terminalTools = new TerminalTools(scanner);
            GitTools gitTools = new GitTools(scanner);
            WebTools webTools = new WebTools(scanner);
            MemoryTools memoryTools = new MemoryTools(embeddingModel, embeddingStore);

            // 4. Instantiate ToolRegistry and register tools
            ToolRegistry toolRegistry = new ToolRegistry(terminalTools, gitTools, webTools, memoryTools);

            // 5. Initialize Chat Model and SubAgentFactory
            ChatLanguageModel model = OllamaModelFactory.createModel();
            SubAgentFactory factory = new SubAgentFactory(model, toolRegistry);

            // 6. Read agents.json from classpath
            ObjectMapper mapper = new ObjectMapper();
            List<AgentConfig> configs;
            try (InputStream is = AgentMain.class.getClassLoader().getResourceAsStream("agents.json")) {
                if (is == null) {
                    throw new IllegalStateException("agents.json not found in classpath resources");
                }
                configs = mapper.readValue(is, new TypeReference<List<AgentConfig>>() {});
            }

            AgentConfig researcherConfig = configs.stream()
                    .filter(c -> "web_researcher".equals(c.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("web_researcher config not found in agents.json"));

            AgentConfig patcherConfig = configs.stream()
                    .filter(c -> "code_patcher".equals(c.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("code_patcher config not found in agents.json"));

            System.out.println("=======================================================");
            System.out.println("Multi-Agent Swarm: SubAgentFactory Dynamic Workers");
            System.out.println("=======================================================");

            // 7. Dynamically spawn specialized sub-agents
            WorkerAgent researcher = factory.createAgent(researcherConfig);
            WorkerAgent patcher = factory.createAgent(patcherConfig);

            // 8. Test the Web Researcher
            System.out.println("\n-------------------------------------------------------");
            System.out.println("Executing Sub-Agent 1: " + researcherConfig.name() + " (Tools: " + researcherConfig.tools() + ")");
            System.out.println("-------------------------------------------------------");
            String researchTask = "Search the web for the Wikipedia page on 'Swarm Intelligence' and summarize it in one sentence.";
            System.out.println("Task > " + researchTask);
            String researchResult = researcher.execute(researchTask);
            System.out.println("\nResearcher Response > " + (researchResult != null ? researchResult.trim() : ""));

            // 9. Prepare dummy.txt and test Code Patcher
            Path dummyPath = Paths.get("dummy.txt");
            Files.writeString(dummyPath, "Line 1: Initial System State\nLine 2: Standby Mode\nLine 3: Operations Nominal\n");

            System.out.println("\n-------------------------------------------------------");
            System.out.println("Executing Sub-Agent 2: " + patcherConfig.name() + " (Tools: " + patcherConfig.tools() + ")");
            System.out.println("Initial dummy.txt content:\n" + Files.readString(dummyPath).trim());
            System.out.println("-------------------------------------------------------");
            String patchTask = "Read lines 1 to 3 of dummy.txt and replace line 2 with 'Agent Swarm Active'.";
            System.out.println("Task > " + patchTask);
            String patchResult = patcher.execute(patchTask);
            System.out.println("\nPatcher Response > " + (patchResult != null ? patchResult.trim() : ""));
            System.out.println("\nPost-patch dummy.txt content:\n" + Files.readString(dummyPath).trim());
            System.out.println("=======================================================");

        } catch (Exception e) {
            log.error("Fatal error during worker agent execution", e);
            System.err.println("ERROR: Could not execute worker agents: " + e.getMessage());
            System.exit(1);
        }
    }
}
