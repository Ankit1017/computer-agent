package com.agent;

import dev.langchain4j.service.SystemMessage;

/**
 * Interface proxy for the Computer Use AI Agent.
 * Configured and bound dynamically via LangChain4j AiServices.
 */
public interface ComputerAgent {

    @SystemMessage("You are an autonomous computer use agent. Keep your responses concise, analytical, and deterministic. Do not use conversational filler. You can use tools to execute terminal commands, read files, and browse directories. If a user asks you to perform an OS action, use the appropriate tool automatically.")
    String chat(String userMessage);
}
