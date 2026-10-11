package com.agent;

import com.agent.factory.OllamaModelFactory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Milestone 1.1: Minimal API Connection verification entry point.
 */
public class AgentMain {

    private static final Logger log = LoggerFactory.getLogger(AgentMain.class);

    public static void main(String[] args) {
        log.info("Starting Computer Use AI Agent - Milestone 1.1 Verification");

        try {
            // Instantiate model via factory
            ChatLanguageModel model = OllamaModelFactory.createModel();

            // Prompt as specified in Milestone 1.1
            String prompt = "System check: Respond with 'Hello World, local Ollama is active.' and nothing else.";
            log.info("Sending prompt to Ollama: \"{}\"", prompt);

            long startTime = System.currentTimeMillis();
            String response = model.generate(prompt);
            long elapsed = System.currentTimeMillis() - startTime;

            log.info("Response received in {} ms", elapsed);

            System.out.println("\n=======================================================");
            System.out.println("OLLAMA CONNECTION VERIFICATION RESULT:");
            System.out.println(response != null ? response.trim() : "<null response>");
            System.out.println("=======================================================\n");

            System.exit(0);
        } catch (Exception e) {
            log.error("Failed to connect or generate response from local Ollama", e);
            System.err.println("ERROR: Could not communicate with Ollama instance: " + e.getMessage());
            System.exit(1);
        }
    }
}
