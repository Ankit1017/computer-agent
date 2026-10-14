package com.agent;

import com.agent.config.AgentConfig;
import com.agent.factory.OllamaModelFactory;
import com.agent.factory.SubAgentFactory;
import com.agent.orchestration.DelegationFacade;
import com.agent.orchestration.OrchestratorPromptBuilder;
import com.agent.orchestration.services.OrchestrationService;
import com.agent.orchestration.workflow.WorkflowToolsFacade;
import com.agent.orchestration.workflow.checkpoint.JsonFileCheckpointer;
import com.agent.orchestration.workflow.services.WorkflowRegistryService;
import com.agent.registry.ToolRegistry;
import com.agent.tools.context.ContextToolsFacade;
import com.agent.tools.context.services.BlackboardService;
import com.agent.tools.git.GitToolsFacade;
import com.agent.tools.git.services.GitExecutionService;
import com.agent.tools.memory.MemoryToolsFacade;
import com.agent.tools.memory.services.LongTermMemoryService;
import com.agent.tools.terminal.TerminalToolsFacade;
import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.terminal.services.CommandExecutorService;
import com.agent.tools.terminal.services.FileSystemService;
import com.agent.tools.vision.VisionToolsFacade;
import com.agent.tools.web.WebToolsFacade;
import com.agent.tools.web.services.WebAnalysisService;
import com.agent.tools.web.services.WebDistillationService;
import com.agent.tools.web.services.WebNavigationService;
import com.agent.tools.web.services.WebSearchService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Agent - Milestone 10.4.2 Hybrid Swarm Orchestrator Active");

        try (Scanner scanner = new Scanner(System.in)) {
            // 1. Core Services & Shutdown
            WebNavigationService navService = new WebNavigationService();
            BlackboardService blackboardService = new BlackboardService();

            // 2. Initialize Tools & SubAgent Factory
            ToolRegistry toolRegistry = initializeTools(scanner, navService, blackboardService);
            ChatLanguageModel model = OllamaModelFactory.createModel();
            SubAgentFactory factory = new SubAgentFactory(model, toolRegistry);

            // 3. Load Configs & Spawn Sub-Agents
            List<AgentConfig> configs = loadAgentConfigs();
            Map<String, WorkerAgent> swarm = spawnSubAgents(configs, factory);

            // 4. Build Hybrid Orchestrator & Workflows
            OrchestrationService orchestrationService = new OrchestrationService(factory, blackboardService);
            JsonFileCheckpointer checkpointer = new JsonFileCheckpointer(Path.of(".checkpoints"));
            WorkflowRegistryService workflowRegistry = new WorkflowRegistryService(orchestrationService, checkpointer);
            WorkflowToolsFacade workflowToolsFacade = new WorkflowToolsFacade(workflowRegistry);

            registerShutdownHook(navService, blackboardService, orchestrationService);
            WorkerAgent orchestrator = buildOrchestrator(model, orchestrationService, workflowToolsFacade, configs, workflowRegistry);

            // 5. Start CLI
            runCliLoop(scanner, orchestrator, swarm, workflowRegistry);

        } catch (Exception e) {
            log.error("Fatal error during swarm orchestrator initialization", e);
            System.err.println("ERROR: Could not initialize orchestrator: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void registerShutdownHook(WebNavigationService navService, BlackboardService blackboardService, OrchestrationService orchestrationService) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SYSTEM] Graceful shutdown initiated. Releasing resources...");
            try {
                if (navService != null) navService.close();
                if (blackboardService != null) blackboardService.shutdown();
                if (orchestrationService != null) orchestrationService.shutdown();
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
            }
            System.out.println("[SYSTEM] Shutdown complete. Goodbye.");
        }));
    }

    private static ToolRegistry initializeTools(Scanner scanner, WebNavigationService navService, BlackboardService blackboardService) {
        SecurityInterceptor security = new SecurityInterceptor(scanner);
        return new ToolRegistry(
                new TerminalToolsFacade(security, new CommandExecutorService(), new FileSystemService()),
                new GitToolsFacade(security, new GitExecutionService()),
                new WebToolsFacade(security, navService, new WebDistillationService(), new WebSearchService(), new WebAnalysisService()),
                new MemoryToolsFacade(new LongTermMemoryService()),
                new ContextToolsFacade(blackboardService),
                new VisionToolsFacade(security)
        );
    }

    private static List<AgentConfig> loadAgentConfigs() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path localConfig = Path.of("agents.json");
        InputStream configStream;
        
        if (Files.exists(localConfig)) {
            System.out.println("[CONFIG] Loading agents manifest from working directory: " + localConfig.toAbsolutePath());
            configStream = Files.newInputStream(localConfig);
        } else {
            System.out.println("[CONFIG] Loading default agents manifest from classpath resources.");
            configStream = AgentMain.class.getClassLoader().getResourceAsStream("agents.json");
            if (configStream == null) throw new IllegalStateException("agents.json not found!");
        }
        
        try (configStream) {
            return mapper.readValue(configStream, new TypeReference<List<AgentConfig>>() {});
        }
    }

    private static Map<String, WorkerAgent> spawnSubAgents(List<AgentConfig> configs, SubAgentFactory factory) {
        Map<String, WorkerAgent> swarm = new HashMap<>();
        for (AgentConfig config : configs) {
            log.info("Spawning swarm sub-agent: '{}' [{}]", config.name(), config.id());
            swarm.put(config.id(), factory.createAgent(config));
        }
        return swarm;
    }

    public static WorkerAgent buildOrchestrator(
            ChatLanguageModel model,
            OrchestrationService orchestrationService,
            List<AgentConfig> configs
    ) {
        WorkflowRegistryService dummyRegistry = new WorkflowRegistryService(orchestrationService);
        WorkflowToolsFacade dummyFacade = new WorkflowToolsFacade(dummyRegistry);
        return buildOrchestrator(model, orchestrationService, dummyFacade, configs, dummyRegistry);
    }

    public static WorkerAgent buildOrchestrator(
            ChatLanguageModel model,
            OrchestrationService orchestrationService,
            WorkflowToolsFacade workflowToolsFacade,
            List<AgentConfig> configs
    ) {
        return buildOrchestrator(model, orchestrationService, workflowToolsFacade, configs, null);
    }

    public static WorkerAgent buildOrchestrator(
            ChatLanguageModel model,
            OrchestrationService orchestrationService,
            WorkflowToolsFacade workflowToolsFacade,
            List<AgentConfig> configs,
            WorkflowRegistryService workflowRegistry
    ) {
        DelegationFacade delegationFacade = new DelegationFacade(orchestrationService);
        String managerPrompt = OrchestratorPromptBuilder.buildPrompt(configs, workflowRegistry);

        var builder = AiServices.builder(WorkerAgent.class)
                .chatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .systemMessageProvider(chatMemoryId -> managerPrompt);

        if (workflowToolsFacade != null) {
            builder.tools(delegationFacade, workflowToolsFacade);
        } else {
            builder.tools(delegationFacade);
        }

        return builder.build();
    }

    private static void runCliLoop(Scanner scanner, WorkerAgent orchestrator, Map<String, WorkerAgent> swarm) {
        runCliLoop(scanner, orchestrator, swarm, null);
    }

    private static void runCliLoop(
            Scanner scanner,
            WorkerAgent orchestrator,
            Map<String, WorkerAgent> swarm,
            WorkflowRegistryService workflowRegistry
    ) {
        System.out.println("=======================================================");
        System.out.println("Swarm Orchestrator CLI Active (Hybrid Mode)");
        System.out.println("Available Sub-Agents: " + swarm.keySet());
        if (workflowRegistry != null && !workflowRegistry.listWorkflowIds().isEmpty()) {
            System.out.println("Registered Workflows: " + workflowRegistry.listWorkflowIds());
        }
        System.out.println("Type your prompt below. Type 'exit' or 'quit' to terminate.");
        System.out.println("=======================================================\n");

        while (true) {
            System.out.print("User > ");
            System.out.flush();

            if (!scanner.hasNextLine()) {
                System.exit(0);
            }

            String input = scanner.nextLine().trim();
            log.info("CLI input received: [{}]", input);

            if (input.isEmpty()) continue;

            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                System.out.println("Orchestrator > Swarm session terminated. Goodbye!");
                System.exit(0);
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
    }
}
