package com.agent.orchestration.workflow.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility parser responsible for extracting structured key-value updates
 * from unstructured or semi-structured LLM responses.
 *
 * <p>Supports:
 * <ul>
 *   <li>Standard JSON markdown code blocks: ```json ... ```</li>
 *   <li>Generic markdown code blocks: ``` ... ```</li>
 *   <li>Raw JSON objects: { ... }</li>
 *   <li>Fallback key mapping for unparseable raw responses</li>
 * </ul>
 */
public class StateUpdateParser {

    private static final Logger log = LoggerFactory.getLogger(StateUpdateParser.class);
    private static final Pattern FENCED_JSON_BLOCK = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE
    );

    private final ObjectMapper objectMapper;

    public StateUpdateParser() {
        this(new ObjectMapper());
    }

    public StateUpdateParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    /**
     * Parses an agent's textual response into a partial state update map.
     *
     * @param rawResponse raw string returned by the sub-agent
     * @param fallbackKey channel key to use if no JSON structure can be extracted
     * @return map of state updates
     */
    public Map<String, Object> parseOutput(String rawResponse, String fallbackKey) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return Collections.emptyMap();
        }

        String trimmed = rawResponse.trim();

        // 1. Try markdown fenced block extraction
        Matcher matcher = FENCED_JSON_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            Map<String, Object> parsed = tryParseJson(candidate);
            if (parsed != null) {
                return parsed;
            }
        }

        // 2. Try raw JSON if string starts and ends with curly braces
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            Map<String, Object> parsed = tryParseJson(trimmed);
            if (parsed != null) {
                return parsed;
            }
        }

        // 3. Try finding any embedded { ... } block in the text
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace > firstBrace) {
            String candidate = trimmed.substring(firstBrace, lastBrace + 1).trim();
            Map<String, Object> parsed = tryParseJson(candidate);
            if (parsed != null) {
                return parsed;
            }
        }

        // 4. Fallback: store raw text under the fallback key
        log.debug("Could not parse JSON from agent response. Using fallback key '{}'", fallbackKey);
        String key = (fallbackKey != null && !fallbackKey.isBlank()) ? fallbackKey.trim() : "raw_output";
        return Map.of(key, trimmed);
    }

    private Map<String, Object> tryParseJson(String jsonCandidate) {
        try {
            return objectMapper.readValue(jsonCandidate, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.trace("JSON parse attempt failed on candidate: {}", e.getMessage());
            return null;
        }
    }
}
