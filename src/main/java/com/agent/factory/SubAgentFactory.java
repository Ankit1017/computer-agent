package com.agent.factory;

import com.agent.WorkerAgent;
import com.agent.config.AgentConfig;
import com.agent.registry.ToolRegistry;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic factory that instantiates specialized WorkerAgent proxies from AgentConfig.
 * Each worker receives isolated memory, its specific toolset, and a dynamic system prompt.
 */
public class SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SubAgentFactory.class);

    private final ChatLanguageModel chatModel;
    private final ToolRegistry toolRegistry;
    private final Map<String, WorkerAgent> agents = new ConcurrentHashMap<>();
    private final Map<String, AgentConfig> configs = new ConcurrentHashMap<>();

    /**
     * Creates a new SubAgentFactory.
     *
     * @param chatModel    the chat language model used by spawned agents
     * @param toolRegistry the centralized tool registry to resolve tool dependencies
     */
    public SubAgentFactory(ChatLanguageModel chatModel, ToolRegistry toolRegistry) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
    }

    /**
     * Creates an isolated WorkerAgent instance configured according to the provided AgentConfig.
     *
     * @param config the sub-agent specification
     * @return an executable WorkerAgent proxy
     */
    public WorkerAgent createAgent(AgentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("AgentConfig must not be null");
        }

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
        return java.util.Collections.unmodifiableMap(configs);
    }
}
