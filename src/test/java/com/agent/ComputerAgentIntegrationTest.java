package com.agent;

import com.agent.factory.OllamaModelFactory;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComputerAgentIntegrationTest {

    @Test
    @DisplayName("Should maintain conversation memory through AiServices proxy with Ollama")
    void shouldMaintainMemoryThroughAiServices() {
        ChatLanguageModel model = OllamaModelFactory.createModel();
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);

        ComputerAgent agent = AiServices.builder(ComputerAgent.class)
                .chatLanguageModel(model)
                .chatMemory(memory)
                .build();

        String reply1 = agent.chat("System register: Project codename is Apollo.");
        assertThat(reply1).isNotNull();

        String reply2 = agent.chat("Recall: What is the project codename?");
        assertThat(reply2).isNotNull();
        assertThat(reply2.toLowerCase()).contains("apollo");
    }
}
