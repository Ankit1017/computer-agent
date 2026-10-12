package com.agent;

import com.agent.factory.OllamaModelFactory;
import com.agent.tools.GitTools;
import com.agent.tools.TerminalTools;
import com.agent.tools.WebTools;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Scanner;

/**
 * Phase 4, Milestone 4.1: Web Scraper with Safe Text Truncation.
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Use AI Agent - Milestone 4.2 Web & API Tools Active");

        try {
            // 1. Shared Scanner for both chat input and HITL tool security confirmation
            Scanner scanner = new Scanner(System.in);

            // 2. Initialize the ChatLanguageModel
            ChatLanguageModel model = OllamaModelFactory.createModel();

            // 3. Initialize the sliding-window ChatMemory
            ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);

            // 4. Instantiate Terminal, Git, and Web Tools with the shared console scanner
            TerminalTools tools = new TerminalTools(scanner);
            GitTools gitTools = new GitTools(scanner);
            WebTools webTools = new WebTools(scanner);

            // 5. Build the ComputerAgent proxy with bound tools
            ComputerAgent agent = AiServices.builder(ComputerAgent.class)
                    .chatLanguageModel(model)
                    .chatMemory(memory)
                    .tools(webTools, tools, gitTools)
                    .build();

            System.out.println("=======================================================");
            System.out.println("Autonomous Computer Use Agent (Terminal Tools Active)");
            System.out.println("Type your prompt below. Type 'exit' or 'quit' to terminate.");
            System.out.println("=======================================================\n");

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

                if ("y".equalsIgnoreCase(input) || "yes".equalsIgnoreCase(input) || "n".equalsIgnoreCase(input) || "no".equalsIgnoreCase(input)) {
                    continue;
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
