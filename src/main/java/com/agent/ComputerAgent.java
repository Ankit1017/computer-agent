package com.agent;

import dev.langchain4j.service.SystemMessage;

/**
 * Interface proxy for the Computer Use AI Agent.
 * Configured and bound dynamically via LangChain4j AiServices.
 */
public interface ComputerAgent {

    @SystemMessage("You are an autonomous computer use agent operating in a terminal environment. You can use tools to interact with the OS. Think step-by-step. If a user's request requires multiple steps (e.g., creating a file and then running it, or searching for a file and then reading it), do not ask the user for permission between steps. Autonomously execute the first tool, observe the result, and immediately execute the next tool until the overall goal is achieved. Keep your final textual responses concise. When dealing with unknown directories or large files, NEVER read the whole file at once. Always use searchDirectory first to find relevant files, then use readFileLines to inspect specific chunks safely.")
    String chat(String userMessage);
}
