package com.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe shared key-value store (Blackboard) enabling sub-agents to share raw payloads,
 * intermediate results, and sensitive credentials without routing massive data through the orchestrator.
 */
public class ContextTools {

    private static final Logger log = LoggerFactory.getLogger(ContextTools.class);

    private final ConcurrentHashMap<String, String> blackboard = new ConcurrentHashMap<>();

    /**
     * Writes or updates a key-value pair in the shared swarm blackboard.
     *
     * @param key   unique key identifier
     * @param value data payload to store
     * @return confirmation message
     */
    @Tool("Write a value to the shared swarm blackboard. Use this to pass large data, IDs, or results to other agents.")
    public String writeToBlackboard(
            @P("The unique key name") String key,
            @P("The data payload") String value) {

        if (key == null || key.isBlank()) {
            return "Error: Key cannot be empty.";
        }

        String trimmedKey = key.trim();
        String payload = value != null ? value : "";
        blackboard.put(trimmedKey, payload);

        log.info("Blackboard write: key='{}', payload length={}", trimmedKey, payload.length());
        System.out.println("\n[BLACKBOARD] Stored key '" + trimmedKey + "' (length: " + payload.length() + ")");
        System.out.flush();

        return "Successfully wrote key '" + trimmedKey + "' to the blackboard.";
    }

    /**
     * Reads a value from the shared swarm blackboard by key.
     *
     * @param key unique key identifier
     * @return the stored data payload or an error if missing
     */
    @Tool("Read a value from the shared swarm blackboard. Use this to retrieve data placed there by other agents.")
    public String readFromBlackboard(@P("The unique key name") String key) {
        if (key == null || key.isBlank()) {
            return "Error: Key cannot be empty.";
        }

        String trimmedKey = key.trim();
        String value = blackboard.get(trimmedKey);

        if (value == null) {
            log.warn("Blackboard read miss for key='{}'", trimmedKey);
            return "Error: Key '" + trimmedKey + "' not found on the blackboard.";
        }

        log.info("Blackboard read hit: key='{}', payload length={}", trimmedKey, value.length());
        System.out.println("\n[BLACKBOARD] Retrieved key '" + trimmedKey + "'");
        System.out.flush();

        return value;
    }

    /**
     * Returns an unmodifiable snapshot view of the blackboard for auditing and diagnostics.
     */
    public Map<String, String> getBlackboard() {
        return Collections.unmodifiableMap(blackboard);
    }
}
