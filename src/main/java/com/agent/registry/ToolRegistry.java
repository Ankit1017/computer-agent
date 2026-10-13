package com.agent.registry;

import com.agent.tools.ContextTools;
import com.agent.tools.GitTools;
import com.agent.tools.MemoryTools;
import com.agent.tools.WebTools;
import com.agent.tools.terminal.TerminalToolsFacade;
import com.agent.tools.vision.VisionToolsFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Centralized registry mapping logical string tool identifiers to instantiated tool singletons.
 * Allows dynamic tool composition for specialized sub-agents defined via AgentConfig.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Object> toolMap = new LinkedHashMap<>();

    /**
     * Initializes the tool registry with standard agent tools.
     *
     * @param terminalTools terminal and filesystem operations facade
     * @param gitTools      git version control operations
     * @param webTools      web scraping and HTTP interaction
     * @param memoryTools   long-term vector memory operations
     */
    public ToolRegistry(TerminalToolsFacade terminalTools, GitTools gitTools, WebTools webTools, MemoryTools memoryTools) {
        this(terminalTools, gitTools, webTools, memoryTools, null);
    }

    /**
     * Initializes the tool registry with standard agent tools and shared context blackboard.
     *
     * @param terminalTools terminal and filesystem operations facade
     * @param gitTools      git version control operations
     * @param webTools      web scraping and HTTP interaction
     * @param memoryTools   long-term vector memory operations
     * @param contextTools  shared blackboard memory operations
     */
    public ToolRegistry(TerminalToolsFacade terminalTools, GitTools gitTools, WebTools webTools, MemoryTools memoryTools, ContextTools contextTools) {
        if (terminalTools != null) {
            registerTool("terminal", terminalTools);
        }
        if (gitTools != null) {
            registerTool("git", gitTools);
        }
        if (webTools != null) {
            registerTool("web", webTools);
        }
        if (memoryTools != null) {
            registerTool("memory", memoryTools);
        }
        if (contextTools != null) {
            registerTool("context", contextTools);
        }
        log.info("Initialized ToolRegistry with available tools: {}", toolMap.keySet());
    }

    /**
     * Initializes the tool registry with standard agent tools, shared context blackboard, and vision tools.
     *
     * @param terminalTools terminal and filesystem operations facade
     * @param gitTools      git version control operations
     * @param webTools      web scraping and HTTP interaction
     * @param memoryTools   long-term vector memory operations
     * @param contextTools  shared blackboard memory operations
     * @param visionTools   Set-of-Marks screen inspection and GUI actuation
     */
    public ToolRegistry(TerminalToolsFacade terminalTools, GitTools gitTools, WebTools webTools, MemoryTools memoryTools, ContextTools contextTools, VisionToolsFacade visionTools) {
        if (terminalTools != null) {
            registerTool("terminal", terminalTools);
        }
        if (gitTools != null) {
            registerTool("git", gitTools);
        }
        if (webTools != null) {
            registerTool("web", webTools);
        }
        if (memoryTools != null) {
            registerTool("memory", memoryTools);
        }
        if (contextTools != null) {
            registerTool("context", contextTools);
        }
        if (visionTools != null) {
            registerTool("vision", visionTools);
        }
        log.info("Initialized ToolRegistry with available tools: {}", toolMap.keySet());
    }

    /**
     * Registers a tool instance under a given name.
     *
     * @param name         logical identifier (case-insensitive)
     * @param toolInstance instantiated tool object
     */
    public void registerTool(String name, Object toolInstance) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be null or blank");
        }
        if (toolInstance == null) {
            throw new IllegalArgumentException("Tool instance must not be null for name: " + name);
        }
        String key = name.trim().toLowerCase();
        toolMap.put(key, toolInstance);
        log.debug("Registered tool '{}' -> {}", key, toolInstance.getClass().getSimpleName());
    }

    /**
     * Retrieves a single tool instance by its logical name.
     *
     * @param name logical tool name
     * @return tool instance, or null if not found
     */
    public Object getTool(String name) {
        if (name == null) {
            return null;
        }
        return toolMap.get(name.trim().toLowerCase());
    }

    /**
     * Resolves a list of requested string tool names into an array of actual Java tool instances.
     *
     * @param requestedTools list of tool string names from AgentConfig
     * @return array of corresponding Java tool objects
     */
    public Object[] getTools(List<String> requestedTools) {
        if (requestedTools == null || requestedTools.isEmpty()) {
            return new Object[0];
        }

        List<Object> resolved = new ArrayList<>();
        for (String toolName : requestedTools) {
            if (toolName == null || toolName.isBlank()) {
                continue;
            }
            String key = toolName.trim().toLowerCase();
            Object tool = toolMap.get(key);
            if (tool != null) {
                resolved.add(tool);
            } else {
                log.warn("Requested tool '{}' not found in ToolRegistry. Available: {}", key, toolMap.keySet());
                throw new IllegalArgumentException("Unknown tool requested: '" + toolName + "'. Available tools: " + toolMap.keySet());
            }
        }
        return resolved.toArray();
    }

    /**
     * Returns an unmodifiable view of all registered tool names.
     *
     * @return set of registered tool names
     */
    public Set<String> getAvailableToolNames() {
        return Collections.unmodifiableSet(toolMap.keySet());
    }
}
