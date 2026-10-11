package com.agent.session;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Manages stateful conversational interactions with an LLM using a sliding-window chat memory.
 * Decouples conversation state management from CLI presentation layers.
 */
public class ChatSession {

    private static final Logger log = LoggerFactory.getLogger(ChatSession.class);
    public static final int DEFAULT_MAX_MESSAGES = 20;

    private final ChatLanguageModel model;
    private final ChatMemory chatMemory;

    /**
     * Initializes a ChatSession with the default sliding window of 20 messages.
     *
     * @param model the configured ChatLanguageModel to invoke
     */
    public ChatSession(ChatLanguageModel model) {
        this(model, DEFAULT_MAX_MESSAGES);
    }

    /**
     * Initializes a ChatSession with a custom window size.
     *
     * @param model       the configured ChatLanguageModel to invoke
     * @param maxMessages maximum number of recent messages to preserve in memory
     */
    public ChatSession(ChatLanguageModel model, int maxMessages) {
        this(model, MessageWindowChatMemory.withMaxMessages(maxMessages));
    }

    /**
     * Initializes a ChatSession with an explicit {@link ChatMemory} implementation.
     *
     * @param model      the configured ChatLanguageModel to invoke
     * @param chatMemory custom ChatMemory instance
     */
    public ChatSession(ChatLanguageModel model, ChatMemory chatMemory) {
        this.model = Objects.requireNonNull(model, "ChatLanguageModel must not be null");
        this.chatMemory = Objects.requireNonNull(chatMemory, "ChatMemory must not be null");
    }

    /**
     * Sends user text into the stateful conversation, updates memory, and returns the agent's reply.
     *
     * @param text raw user prompt
     * @return raw response string from the agent
     */
    public String chat(String text) {
        Objects.requireNonNull(text, "Input text cannot be null");

        // 1. Convert raw string to UserMessage
        UserMessage userMessage = UserMessage.from(text);

        // 2. Add UserMessage to ChatMemory
        chatMemory.add(userMessage);
        log.debug("UserMessage added to memory. Total messages in window: {}", chatMemory.messages().size());

        // 3. Generate response with full memory history
        Response<AiMessage> response = model.generate(chatMemory.messages());

        // 4. Extract AiMessage
        AiMessage aiMessage = response.content();

        // 5. Add AiMessage back to ChatMemory so the agent remembers its own answer
        chatMemory.add(aiMessage);
        log.debug("AiMessage added to memory. Total messages in window: {}", chatMemory.messages().size());

        // 6. Return response text
        return aiMessage.text();
    }

    /**
     * Returns an unmodifiable snapshot of the current message history.
     */
    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(chatMemory.messages());
    }

    /**
     * Clears all conversational history from memory.
     */
    public void clear() {
        chatMemory.clear();
        log.debug("Chat memory cleared.");
    }
}
