package com.agent;

import com.agent.factory.OllamaModelFactory;
import com.agent.session.ChatSession;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Milestone 1.2: Interactive CLI Chat Loop with Stateful Chat Memory.
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Use AI Agent - Milestone 1.2 Interactive CLI Loop");

        try {
            // Instantiate model via factory
            ChatLanguageModel model = OllamaModelFactory.createModel();

            // Instantiate stateful chat session with memory
            ChatSession chatSession = new ChatSession(model);

            System.out.println("=======================================================");
            System.out.println("Computer Use AI Agent - Local Terminal Session Active");
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
                    String response = chatSession.chat(input);
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
