package com.agent.session;

import com.agent.factory.OllamaModelFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionMemoryIntegrationTest {

    @Test
    @DisplayName("Should recall information from earlier turns in the conversation")
    void shouldRecallProjectCodenameAcrossTurns() {
        ChatLanguageModel model = OllamaModelFactory.createModel();
        ChatSession session = new ChatSession(model);

        String reply1 = session.chat("Hello, my project's secret codename is Apollo.");
        assertThat(reply1).isNotNull();

        String reply2 = session.chat("What is the secret codename of my project?");
        assertThat(reply2).isNotNull();
        assertThat(reply2.toLowerCase()).contains("apollo");
    }
}
