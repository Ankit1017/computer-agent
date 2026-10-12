package com.agent.config;

import java.util.List;

/**
 * Immutable configuration blueprint for a specialized AI sub-agent.
 *
 * @param id            unique identifier for the agent (e.g., "web_researcher")
 * @param name          human-readable name for the agent (e.g., "Web Researcher")
 * @param systemMessage specialized system prompt directing agent behavior
 * @param tools         list of string tool identifiers requested by this agent (e.g., ["web", "memory"])
 */
public record AgentConfig(
        String id,
        String name,
        String systemMessage,
        List<String> tools
) {
    public AgentConfig {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
