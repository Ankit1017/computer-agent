package com.agent.factory;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaModelFactoryTest {

    @Test
    @DisplayName("Should create default ChatLanguageModel instance")
    void shouldCreateDefaultModel() {
        ChatLanguageModel model = OllamaModelFactory.createModel();
        assertThat(model).isNotNull();
    }

    @Test
    @DisplayName("Should create ChatLanguageModel with custom parameters")
    void shouldCreateCustomModel() {
        ChatLanguageModel model = OllamaModelFactory.createModel(
                "http://localhost:11434",
                "llama3.1",
                0.0,
                Duration.ofMinutes(2)
        );
        assertThat(model).isNotNull();
    }
}
