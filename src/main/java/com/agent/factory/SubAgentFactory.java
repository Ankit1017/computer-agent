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

import java.util.Objects;

/**
 * Dynamic factory that instantiates specialized WorkerAgent proxies from AgentConfig.
 * Each worker receives isolated memory, its specific toolset, and a dynamic system prompt.
 */
public class SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SubAgentFactory.class);

    private final ChatLanguageModel chatModel;
    private final ToolRegistry toolRegistry;

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

        return AiServices.builder(WorkerAgent.class)
                .chatLanguageModel(chatModel)
                .chatMemory(memory)
                .tools(tools)
                .systemMessageProvider(chatMemoryId -> config.systemMessage())
                .build();
    }
}
