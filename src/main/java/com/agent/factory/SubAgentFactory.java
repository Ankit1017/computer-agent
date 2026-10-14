package com.agent.factory;

import com.agent.WorkerAgent;
import com.agent.config.AgentConfig;
import com.agent.registry.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic factory that instantiates specialized WorkerAgent proxies from AgentConfig.
 * Each worker receives isolated memory, its specific toolset, and a dynamic system prompt.
 */
public class SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SubAgentFactory.class);

    public static final Set<String> VALID_TOOL_CATEGORIES = Set.of(
            "terminal", "git", "web", "memory", "context", "vision"
    );

    private final ChatLanguageModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Map<String, WorkerAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, AgentConfig> configs = new ConcurrentHashMap<>();
    private final Set<String> customAgentIds = ConcurrentHashMap.newKeySet();
    private Path customAgentsPath;

    /**
     * Creates a new SubAgentFactory without disk persistence.
     *
     * @param chatModel    the chat language model used by spawned agents
     * @param toolRegistry the centralized tool registry to resolve tool dependencies
     */
    public SubAgentFactory(ChatLanguageModel chatModel, ToolRegistry toolRegistry) {
        this(chatModel, toolRegistry, null, new ObjectMapper());
    }

    /**
     * Creates a new SubAgentFactory with optional disk persistence path.
     *
     * @param chatModel        the chat language model used by spawned agents
     * @param toolRegistry     the centralized tool registry to resolve tool dependencies
     * @param customAgentsPath optional path to persist and load custom agents
     */
    public SubAgentFactory(ChatLanguageModel chatModel, ToolRegistry toolRegistry, Path customAgentsPath) {
        this(chatModel, toolRegistry, customAgentsPath, new ObjectMapper());
    }

    /**
     * Creates a new SubAgentFactory with optional disk persistence and custom ObjectMapper.
     *
     * @param chatModel        the chat language model used by spawned agents
     * @param toolRegistry     the centralized tool registry to resolve tool dependencies
     * @param customAgentsPath optional path to persist and load custom agents
     * @param objectMapper     custom ObjectMapper instance
     */
    public SubAgentFactory(ChatLanguageModel chatModel, ToolRegistry toolRegistry, Path customAgentsPath, ObjectMapper objectMapper) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.customAgentsPath = customAgentsPath;
        if (this.customAgentsPath != null) {
            loadCustomAgents();
        }
    }

    /**
     * Validates an AgentConfig specification against required attributes and valid tool categories.
     *
     * @param config the sub-agent specification to validate
     * @throws IllegalArgumentException if the configuration is invalid or contains unknown tool categories
     */
    public void validateConfig(AgentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("AgentConfig must not be null");
        }
        if (config.id() == null || config.id().isBlank()) {
            throw new IllegalArgumentException("Agent ID cannot be null or blank");
        }
        if (config.name() == null || config.name().isBlank()) {
            throw new IllegalArgumentException("Agent name cannot be null or blank");
        }
        if (config.systemMessage() == null || config.systemMessage().isBlank()) {
            throw new IllegalArgumentException("Agent systemMessage cannot be null or blank");
        }
        if (config.tools() == null) {
            throw new IllegalArgumentException("Agent tools list cannot be null");
        }
        Set<String> availableTools = toolRegistry != null ? toolRegistry.getAvailableToolNames() : Collections.emptySet();
        for (String tool : config.tools()) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            String key = tool.trim().toLowerCase();
            if (!VALID_TOOL_CATEGORIES.contains(key) && !availableTools.contains(key)) {
                throw new IllegalArgumentException("Unknown tool category requested: '" + tool
                        + "'. Valid tool categories: " + VALID_TOOL_CATEGORIES);
            }
        }
    }

    /**
     * Creates and hot-registers a custom agent dynamically at runtime, tracking it for disk persistence.
     *
     * @param config the custom sub-agent specification
     * @return the created WorkerAgent proxy
     */
    public WorkerAgent createCustomAgent(AgentConfig config) {
        validateConfig(config);
        WorkerAgent agent = createAgent(config);
        customAgentIds.add(config.id().trim().toLowerCase());
        persistCustomAgents();
        log.info("[SUB-AGENT-FACTORY] Successfully created and registered custom agent '{}'", config.id());
        return agent;
    }

    /**
     * Creates an isolated WorkerAgent instance configured according to the provided AgentConfig.
     *
     * @param config the sub-agent specification
     * @return an executable WorkerAgent proxy
     */
    public WorkerAgent createAgent(AgentConfig config) {
        validateConfig(config);

        log.info("Creating WorkerAgent: '{}' (id: '{}')", config.name(), config.id());

        Object[] tools = toolRegistry.getTools(config.tools());
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(10);

        WorkerAgent agent = AiServices.builder(WorkerAgent.class)
                .chatLanguageModel(chatModel)
                .chatMemory(memory)
                .tools(tools)
                .systemMessageProvider(chatMemoryId -> config.systemMessage())
                .build();

        String normalizedId = config.id().trim().toLowerCase();
        configs.put(normalizedId, config);
        agents.put(normalizedId, agent);
        return agent;
    }

    /**
     * Retrieves an active WorkerAgent by ID (case-insensitive and trimmed).
     *
     * @param agentId unique agent identifier
     * @return the WorkerAgent proxy, or null if not found
     */
    public WorkerAgent getAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        return agents.get(agentId.trim().toLowerCase());
    }

    /**
     * Registers a custom WorkerAgent instance under the given ID (case-insensitive and trimmed).
     * Useful for testing and custom proxy registration.
     *
     * @param agentId unique agent identifier
     * @param agent   WorkerAgent instance
     */
    public void registerAgent(String agentId, WorkerAgent agent) {
        if (agentId != null && !agentId.isBlank() && agent != null) {
            agents.put(agentId.trim().toLowerCase(), agent);
        }
    }

    /**
     * Registers a custom WorkerAgent instance and its associated AgentConfig.
     *
     * @param config the sub-agent specification
     * @param agent  WorkerAgent instance
     */
    public void registerAgent(AgentConfig config, WorkerAgent agent) {
        if (config != null && config.id() != null && !config.id().isBlank()) {
            String normalizedId = config.id().trim().toLowerCase();
            configs.put(normalizedId, config);
            if (agent != null) {
                agents.put(normalizedId, agent);
            }
        }
    }

    /**
     * Registers an AgentConfig without immediately creating an active WorkerAgent proxy.
     *
     * @param config the sub-agent specification
     */
    public void registerConfig(AgentConfig config) {
        if (config != null && config.id() != null && !config.id().isBlank()) {
            configs.put(config.id().trim().toLowerCase(), config);
        }
    }

    /**
     * Retrieves an AgentConfig by ID (case-insensitive and trimmed).
     *
     * @param agentId unique agent identifier
     * @return the AgentConfig, or null if not found
     */
    public AgentConfig getConfig(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return null;
        }
        return configs.get(agentId.trim().toLowerCase());
    }

    /**
     * Returns an unmodifiable map of registered AgentConfigs.
     */
    public Map<String, AgentConfig> getConfigs() {
        return Collections.unmodifiableMap(configs);
    }

    /**
     * Returns a list of all registered AgentConfig specifications.
     */
    public List<AgentConfig> listConfigs() {
        return List.copyOf(configs.values());
    }

    /**
     * Returns the set of agent IDs created as custom agents at runtime.
     */
    public Set<String> getCustomAgentIds() {
        return Collections.unmodifiableSet(customAgentIds);
    }

    public Path getCustomAgentsPath() {
        return customAgentsPath;
    }

    public void setCustomAgentsPath(Path customAgentsPath) {
        this.customAgentsPath = customAgentsPath;
    }

    /**
     * Persists all dynamically created custom agents to the configured JSON file.
     */
    public synchronized void persistCustomAgents() {
        if (customAgentsPath == null || customAgentIds.isEmpty()) {
            return;
        }
        try {
            Path parent = customAgentsPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            List<AgentConfig> customConfigs = configs.values().stream()
                    .filter(c -> customAgentIds.contains(c.id().trim().toLowerCase()))
                    .sorted(Comparator.comparing(AgentConfig::id))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(customAgentsPath.toFile(), customConfigs);
            log.info("[SUB-AGENT-FACTORY] Persisted {} custom agent(s) to: {}", customConfigs.size(), customAgentsPath);
        } catch (IOException e) {
            log.warn("[SUB-AGENT-FACTORY] Failed to persist custom agents to '{}': {}", customAgentsPath, e.getMessage());
        }
    }

    /**
     * Loads and registers custom agents from the configured JSON file.
     *
     * @return the number of custom agents loaded
     */
    public synchronized int loadCustomAgents() {
        if (customAgentsPath == null || !Files.exists(customAgentsPath)) {
            return 0;
        }
        try {
            List<AgentConfig> loaded = objectMapper.readValue(
                    customAgentsPath.toFile(),
                    new TypeReference<List<AgentConfig>>() {}
            );
            int count = 0;
            for (AgentConfig cfg : loaded) {
                if (cfg != null && cfg.id() != null && !cfg.id().isBlank()) {
                    customAgentIds.add(cfg.id().trim().toLowerCase());
                    createAgent(cfg);
                    count++;
                }
            }
            log.info("[SUB-AGENT-FACTORY] Loaded {} custom agent(s) from: {}", count, customAgentsPath);
            return count;
        } catch (Exception e) {
            log.warn("[SUB-AGENT-FACTORY] Failed to load custom agents from '{}': {}", customAgentsPath, e.getMessage());
            return 0;
        }
    }
}
