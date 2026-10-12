package com.agent;

import com.agent.config.AgentConfig;
import com.agent.factory.OllamaModelFactory;
import com.agent.factory.SubAgentFactory;
import com.agent.registry.ToolRegistry;
import com.agent.tools.DelegationTool;
import com.agent.tools.GitTools;
import com.agent.tools.MemoryTools;
import com.agent.tools.TerminalTools;
import com.agent.tools.WebTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.service.AiServices;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Phase 7, Milestone 7.2: The Swarm Orchestrator Verification.
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Agent - Milestone 7.2 Swarm Orchestrator Active");

        try {
            // 1. Shared Scanner for both chat input and HITL tool security confirmation
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

            // 7. Initialize the Swarm map of specialized sub-agents
            Map<String, WorkerAgent> swarm = new HashMap<>();
            for (AgentConfig config : configs) {
                log.info("Spawning swarm sub-agent: '{}' [{}]", config.name(), config.id());
                swarm.put(config.id(), factory.createAgent(config));
            }

            // 8. Create the Delegation Tool holding the swarm
            DelegationTool delegationTool = new DelegationTool(swarm);

            // 9. Build the Manager's dynamic prompt
            StringBuilder managerPrompt = new StringBuilder(
                    "You are the Swarm Orchestrator. You do not have direct access to the OS, filesystem, or web.\n" +
                    "To fulfill the user's request, you must delegate tasks to your specialized sub-agents using the delegateTask tool.\n\n" +
                    "Available agents:\n"
            );
            for (AgentConfig config : configs) {
                managerPrompt.append("- ").append(config.id()).append(": ").append(config.name())
                        .append(" - ").append(config.systemMessage()).append("\n");
            }
            managerPrompt.append("\nChain delegations if necessary. Synthesize their responses into a final answer for the user.");

            // 10. Instantiate the Swarm Orchestrator
            WorkerAgent orchestrator = AiServices.builder(WorkerAgent.class)
                    .chatLanguageModel(model)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                    .tools(delegationTool)
                    .systemMessageProvider(chatMemoryId -> managerPrompt.toString())
                    .build();

            // 11. Prepare fresh dummy.txt with 3 lines of text
            Path dummyPath = Paths.get("dummy.txt");
            Files.writeString(dummyPath, "Line 1: Initial System State\nLine 2: Server Port Pending\nLine 3: Operations Nominal\n");

            System.out.println("=======================================================");
            System.out.println("Swarm Orchestrator CLI Active");
            System.out.println("Available Sub-Agents: " + swarm.keySet());
            System.out.println("Type your prompt below. Type 'exit' or 'quit' to terminate.");
            System.out.println("=======================================================\n");

            // 12. Interactive CLI loop
            while (true) {
                System.out.print("User > ");
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                    System.out.println("Orchestrator > Swarm session terminated. Goodbye!");
                    break;
                }

                if ("y".equalsIgnoreCase(input) || "yes".equalsIgnoreCase(input) || "n".equalsIgnoreCase(input) || "no".equalsIgnoreCase(input)) {
                    continue;
                }

                try {
                    String response = orchestrator.execute(input);
                    System.out.println("\nOrchestrator > " + (response != null ? response.trim() : ""));
                    System.out.println();
                } catch (Exception e) {
                    log.error("Error executing orchestrator prompt", e);
                    System.err.println("Orchestrator > Error communicating with swarm: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Fatal error during swarm orchestrator initialization", e);
            System.err.println("ERROR: Could not initialize orchestrator: " + e.getMessage());
            System.exit(1);
        }
    }
}
