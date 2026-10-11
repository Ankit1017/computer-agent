package com.agent.session;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatSessionTest {

    @Test
    @DisplayName("Should store user and assistant messages in chat memory across multi-turn conversation")
    void shouldRetainConversationInMemory() {
        // Mock model that records received messages and replies with turn count
        List<List<ChatMessage>> receivedMessageHistories = new ArrayList<>();
        ChatLanguageModel mockModel = new ChatLanguageModel() {
            private int turn = 0;

            @Override
            public Response<AiMessage> generate(List<ChatMessage> messages) {
                receivedMessageHistories.add(new ArrayList<>(messages));
                turn++;
                return Response.from(AiMessage.from("Response " + turn));
            }

            @Override
            public String generate(String userMessage) {
                return "legacy";
            }
        };

        ChatSession session = new ChatSession(mockModel);

        String reply1 = session.chat("Hello turn 1");
        assertThat(reply1).isEqualTo("Response 1");

        String reply2 = session.chat("Hello turn 2");
        assertThat(reply2).isEqualTo("Response 2");

        // Verify that memory contains 4 messages: User1, Ai1, User2, Ai2
        List<ChatMessage> storedMessages = session.getMessages();
        assertThat(storedMessages).hasSize(4);
        assertThat(storedMessages.get(0).type()).isEqualTo(ChatMessageType.USER);
        assertThat(storedMessages.get(1).type()).isEqualTo(ChatMessageType.AI);
        assertThat(storedMessages.get(2).type()).isEqualTo(ChatMessageType.USER);
        assertThat(storedMessages.get(3).type()).isEqualTo(ChatMessageType.AI);

        // Verify that the second call received the full history of turn 1 plus turn 2's user prompt
        assertThat(receivedMessageHistories.get(1)).hasSize(3);
    }
}
