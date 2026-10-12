package com.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComputerAgentTest {

    @Test
    @DisplayName("Should inject SystemMessage and retain chat memory across turns via AiServices")
    void shouldInjectSystemMessageAndRetainMemory() {
        List<List<ChatMessage>> capturedHistories = new ArrayList<>();

        ChatLanguageModel mockModel = new ChatLanguageModel() {
            private int turn = 0;

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                capturedHistories.add(new ArrayList<>(messages));
                turn++;
                return Response.from(AiMessage.from("Agent reply " + turn));
            }

            @Override
            public String generate(String userMessage) {
                return "legacy";
            }
        };

        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);

        ComputerAgent agent = AiServices.builder(ComputerAgent.class)
                .chatLanguageModel(mockModel)
                .chatMemory(memory)
                .build();

        String reply1 = agent.chat("System ping");
        assertThat(reply1).isEqualTo("Agent reply 1");

        // First turn should contain SystemMessage and UserMessage
        List<ChatMessage> firstTurnMessages = capturedHistories.get(0);
        assertThat(firstTurnMessages).hasSize(2);
        assertThat(firstTurnMessages.get(0).type()).isEqualTo(ChatMessageType.SYSTEM);
        assertThat(firstTurnMessages.get(0).text()).contains("CORE DIRECTIVES");
        assertThat(firstTurnMessages.get(1).type()).isEqualTo(ChatMessageType.USER);
        assertThat(firstTurnMessages.get(1).text()).isEqualTo("System ping");

        String reply2 = agent.chat("System status");
        assertThat(reply2).isEqualTo("Agent reply 2");

        // Second turn should contain SystemMessage + User1 + Ai1 + User2
        List<ChatMessage> secondTurnMessages = capturedHistories.get(1);
        assertThat(secondTurnMessages).hasSize(4);
    }
}
