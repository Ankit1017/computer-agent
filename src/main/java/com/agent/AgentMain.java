package com.agent;

import com.agent.factory.OllamaModelFactory;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Milestone 1.3: Autonomous Computer Use Agent with AiServices Abstraction.
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Use AI Agent - Milestone 1.3 AiServices Abstraction");

        try {
            // 1. Initialize the ChatLanguageModel
            ChatLanguageModel model = OllamaModelFactory.createModel();

            // 2. Initialize the sliding-window ChatMemory
            ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);

            // 3. Build the ComputerAgent proxy using AiServices
            ComputerAgent agent = AiServices.builder(ComputerAgent.class)
                    .chatLanguageModel(model)
                    .chatMemory(memory)
                    .build();

            System.out.println("=======================================================");
            System.out.println("Autonomous Computer Use Agent (AiServices) Active");
            System.out.println("Type your prompt below. Type 'exit' or 'quit' to terminate.");
            System.out.println("=======================================================\n");

            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("User > ");
                System.out.flush();

                if (!scanner.hasNextLine()) {
                    break;
                }

                String input = scanner.nextLine().trim();

                if (input.isEmpty()) {
                    continue;
                }

                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                    System.out.println("Agent > Session ended. Goodbye!");
                    break;
                }

                try {
                    String response = agent.chat(input);
                    System.out.println("Agent > " + (response != null ? response.trim() : ""));
                    System.out.println();
                } catch (Exception e) {
                    log.error("Error generating response from agent", e);
                    System.err.println("Agent > Error communicating with local model: " + e.getMessage());
                }
            }

            Runtime.getRuntime().halt(0);
        } catch (Exception e) {
            log.error("Fatal error during agent initialization", e);
            System.err.println("ERROR: Could not initialize agent session: " + e.getMessage());
            System.exit(1);
        }
    }
}
