package com.agent.tools.vision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, Jackson-serializable record representing a reusable parameterized desktop CUA skill.
 *
 * @param skillId Unique identifier (e.g. "browser_navigate_url", "notepad_write_save")
 * @param name Human-readable name
 * @param description Detailed description of what the skill does
 * @param osPlatform Target OS platform: "ALL", "WINDOWS", "LINUX", "MAC" (default: "ALL")
 * @param parameterNames Names of required dynamic parameters (e.g. ["url"], ["content"])
 * @param steps Sequence of GUI action steps defining the procedural skill
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DesktopSkill(
        @JsonProperty("skillId") String skillId,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("osPlatform") String osPlatform,
        @JsonProperty("parameterNames") List<String> parameterNames,
        @JsonProperty("steps") List<GuiActionStep> steps
) {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    public DesktopSkill {
        if (osPlatform == null || osPlatform.isBlank()) {
            osPlatform = "ALL";
        }
        parameterNames = (parameterNames != null) ? List.copyOf(parameterNames) : List.of();
        steps = (steps != null) ? List.copyOf(steps) : List.of();
    }

    /**
     * Resolves {{placeholder}} parameters in all string fields of the skill's action steps.
     *
     * @param parameters Map containing parameter key-value pairs
     * @return Resolved list of GUI action steps ready for execution
     * @throws IllegalArgumentException if any required parameter in parameterNames is missing or null
     */
    public List<GuiActionStep> resolveSteps(Map<String, String> parameters) {
        Map<String, String> safeParams = (parameters != null) ? parameters : Collections.emptyMap();

        for (String requiredParam : parameterNames) {
            if (!safeParams.containsKey(requiredParam) || safeParams.get(requiredParam) == null) {
                throw new IllegalArgumentException("Missing required parameter '" + requiredParam + "' for skill '" + skillId + "'");
            }
        }

        List<GuiActionStep> resolved = new ArrayList<>();
        for (GuiActionStep step : steps) {
            resolved.add(resolveStep(step, safeParams));
        }
        return Collections.unmodifiableList(resolved);
    }

    private GuiActionStep resolveStep(GuiActionStep step, Map<String, String> params) {
        if (step == null) {
            return null;
        }
        return new GuiActionStep(
                step.action(),
                step.normX(),
                step.normY(),
                step.endNormX(),
                step.endNormY(),
                step.gridId(),
                substitute(step.button(), params),
                step.clickCount(),
                substitute(step.windowQuery(), params),
                substitute(step.windowAction(), params),
                step.offsetPercentX(),
                step.offsetPercentY(),
                substitute(step.text(), params),
                step.clearFirst(),
                step.pressEnter(),
                substitute(step.hotkey(), params),
                step.notches(),
                step.waitMs()
        );
    }

    private String substitute(String input, Map<String, String> params) {
        if (input == null || !input.contains("{{")) {
            return input;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = params.getOrDefault(key, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
