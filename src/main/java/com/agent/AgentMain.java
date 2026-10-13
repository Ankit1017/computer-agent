package com.agent;

import com.agent.config.AgentConfig;
import com.agent.factory.OllamaModelFactory;
import com.agent.factory.SubAgentFactory;
import com.agent.registry.ToolRegistry;
import com.agent.tools.ContextTools;
import com.agent.tools.DelegationTool;
import com.agent.tools.GitTools;
import com.agent.tools.MemoryTools;
import com.agent.tools.terminal.TerminalToolsFacade;
import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.terminal.services.CommandExecutorService;
import com.agent.tools.terminal.services.FileSystemService;
import com.agent.tools.vision.VisionToolsFacade;
import com.agent.tools.web.WebToolsFacade;
import com.agent.tools.web.services.WebDistillationService;
import com.agent.tools.web.services.WebNavigationService;
import com.agent.tools.web.services.WebSearchService;
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
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Agent - Milestone 7.2 Swarm Orchestrator Active");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SYSTEM] Graceful shutdown initiated. Releasing resources...");
            // If you have a global ExecutorService for virtual threads, call .shutdownNow() here.
            // If you need to cleanly disconnect from ChromaDB, invoke the close methods here.
            System.out.println("[SYSTEM] Shutdown complete. Goodbye.");
        }));

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
            SecurityInterceptor security = new SecurityInterceptor(scanner);
            CommandExecutorService cmdService = new CommandExecutorService();
            FileSystemService fsService = new FileSystemService();
            TerminalToolsFacade terminalTools = new TerminalToolsFacade(security, cmdService, fsService);
            GitTools gitTools = new GitTools(scanner);
            WebNavigationService webNavService = new WebNavigationService();
            WebDistillationService webDistService = new WebDistillationService();
            WebSearchService webSearchService = new WebSearchService();
            WebToolsFacade webTools = new WebToolsFacade(security, webNavService, webDistService, webSearchService);
            MemoryTools memoryTools = new MemoryTools(embeddingModel, embeddingStore);
            ContextTools contextTools = new ContextTools();
            VisionToolsFacade visionTools = new VisionToolsFacade(security);

            // 4. Instantiate ToolRegistry and register tools
            ToolRegistry toolRegistry = new ToolRegistry(terminalTools, gitTools, webTools, memoryTools, contextTools, visionTools);

            // 5. Initialize Chat Model and SubAgentFactory
            ChatLanguageModel model = OllamaModelFactory.createModel();
            SubAgentFactory factory = new SubAgentFactory(model, toolRegistry);

            // 6. Read agents.json from working directory or fallback to classpath
            ObjectMapper mapper = new ObjectMapper();
            List<AgentConfig> configs;
            InputStream configStream;
            Path localConfig = Path.of("agents.json");
            if (Files.exists(localConfig)) {
                System.out.println("[CONFIG] Loading agents manifest from working directory: " + localConfig.toAbsolutePath());
                configStream = Files.newInputStream(localConfig);
            } else {
                System.out.println("[CONFIG] Loading default agents manifest from classpath resources.");
                configStream = AgentMain.class.getClassLoader().getResourceAsStream("agents.json");
                if (configStream == null) {
                    throw new IllegalStateException("agents.json not found on disk or classpath!");
                }
            }
            try (configStream) {
                configs = mapper.readValue(configStream, new TypeReference<List<AgentConfig>>() {});
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
                    "You are the Swarm Orchestrator. You do not have direct access to the OS or web.\n" +
                    "To fulfill the user's request, immediately delegate tasks to the appropriate sub-agents using delegateTask.\n" +
                    "Whenever the user asks an agent to do something (e.g. 'Have the <agent>...', 'Tell <agent> to...', 'Ask <agent> to...'), you MUST delegate the instruction to that sub-agent immediately.\n" +
                    "CRITICAL: If you have multiple tasks that do not depend on each other, you MUST use the delegateParallel tool to execute them simultaneously.\n" +
                    "BLACKBOARD: Your sub-agents share a Blackboard via the 'context' tool. If Agent A needs to pass a large piece of data or sensitive credential to Agent B, instruct Agent A to write it to the blackboard with a specific key, and instruct Agent B to read from that key.\n" +
                    "Available agents:\n"
            );
            for (AgentConfig config : configs) {
                managerPrompt.append("- ").append(config.id()).append(": ").append(config.name())
                        .append(" - ").append(config.systemMessage()).append("\n");
            }
            managerPrompt.append("\nWhen tasks are independent, ALWAYS use delegateParallel. When tasks depend on prior output, chain them sequentially. Synthesize their responses into a final answer.\n\n")
                    .append("HOST OS & COMMAND EXECUTION:\n")
                    .append("Host OS is: ").append(System.getProperty("os.name")).append(".\n")
                    .append("When requesting terminal commands from devops, ensure syntax matches the host OS (e.g. on Windows use 'ping -n 4' instead of '-c 4', and for sleep/delay use 'powershell -Command Start-Sleep -Seconds 65' instead of 'sleep 65'). Always delegate distinct sequential steps as separate tasks (run ping first, then run sleep/timeout).\n\n")
                    .append("GOAL ADHERENCE & ANTI-LOOPING:\n")
                    .append("1. Never repeat the exact same delegation if it failed, returned no useful data, OR if the user denied permission via a security interceptor. If an action is denied, inform the user and ask how they want to proceed.\n")
                    .append("2. If a sub-agent returns a massive chunk of irrelevant data, ignore the irrelevant parts and stick strictly to your original goal.\n")
                    .append("3. To understand directory structure, delegate 'listDirectory'. To search file contents for text or regex patterns, delegate 'searchDirectory'.\n");

            // 10. Instantiate the Swarm Orchestrator
            WorkerAgent orchestrator = AiServices.builder(WorkerAgent.class)
                    .chatLanguageModel(model)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                    .tools(delegationTool)
                    .systemMessageProvider(chatMemoryId -> managerPrompt.toString())
                    .build();

            // 11. Prepare fresh dummy.txt with 3 lines of text
            Path dummyPath = Paths.get("dummy.txt");
            Files.writeString(dummyPath, "Line 1: Initial System State\nLine 2: Server Port 5000\nLine 3: Java Target Pending\n");

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
                log.info("CLI input received: [{}]", input);

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
