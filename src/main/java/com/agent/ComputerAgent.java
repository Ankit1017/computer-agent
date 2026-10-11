package com.agent;

import dev.langchain4j.service.SystemMessage;

/**
 * Interface proxy for the Computer Use AI Agent.
 * Configured and bound dynamically via LangChain4j AiServices.
 */
public interface ComputerAgent {

    @SystemMessage("You are an autonomous computer use agent. Keep your responses concise, analytical, and deterministic. Do not use conversational filler.")
    String chat(String userMessage);
}
