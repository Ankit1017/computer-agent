package com.agent.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Immutable configuration blueprint for a specialized AI sub-agent,
 * supporting scoped state contracts (readChannels and writeChannels).
 *
 * @param id            unique identifier for the agent (e.g., "web_researcher")
 * @param name          human-readable name for the agent (e.g., "Web Researcher")
 * @param systemMessage specialized system prompt directing agent behavior
 * @param tools         list of string tool identifiers requested by this agent (e.g., ["web", "memory"])
 * @param readChannels  optional list of channels this agent is permitted to read in a workflow state (empty = unrestricted)
 * @param writeChannels optional list of channels this agent is permitted to update in a workflow state (empty = unrestricted)
 */
public record AgentConfig(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("systemMessage") String systemMessage,
        @JsonProperty("tools") List<String> tools,
        @JsonProperty("readChannels") List<String> readChannels,
        @JsonProperty("writeChannels") List<String> writeChannels
) {

    @JsonCreator
    public AgentConfig(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("systemMessage") String systemMessage,
            @JsonProperty("tools") List<String> tools,
            @JsonProperty("readChannels") List<String> readChannels,
            @JsonProperty("writeChannels") List<String> writeChannels
    ) {
        this.id = id != null ? id.trim() : null;
        this.name = name != null ? name.trim() : null;
        this.systemMessage = systemMessage != null ? systemMessage.trim() : null;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.readChannels = readChannels == null ? List.of() : List.copyOf(readChannels);
        this.writeChannels = writeChannels == null ? List.of() : List.copyOf(writeChannels);
    }

    public AgentConfig(String id, String name, String systemMessage, List<String> tools) {
        this(id, name, systemMessage, tools, List.of(), List.of());
    }
}
