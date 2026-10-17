package com.agent.tools.vision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Representation of a single GUI action step inside a compound CUA macro.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuiActionStep(
        @JsonProperty("action") String action,
        @JsonProperty("normX") Integer normX,
        @JsonProperty("normY") Integer normY,
        @JsonProperty("endNormX") Integer endNormX,
        @JsonProperty("endNormY") Integer endNormY,
        @JsonProperty("gridId") Integer gridId,
        @JsonProperty("button") String button,
        @JsonProperty("clickCount") Integer clickCount,
        @JsonProperty("windowQuery") String windowQuery,
        @JsonProperty("windowAction") String windowAction,
        @JsonProperty("offsetPercentX") Double offsetPercentX,
        @JsonProperty("offsetPercentY") Double offsetPercentY,
        @JsonProperty("text") String text,
        @JsonProperty("clearFirst") Boolean clearFirst,
        @JsonProperty("pressEnter") Boolean pressEnter,
        @JsonProperty("hotkey") String hotkey,
        @JsonProperty("notches") Integer notches,
        @JsonProperty("waitMs") Integer waitMs
) {

    public static GuiActionStep focusWindow(String windowQuery) {
        return new GuiActionStep("focus_window", null, null, null, null, null, null, null, windowQuery, null, null, null, null, null, null, null, null, null);
    }

    public static GuiActionStep controlWindow(String windowQuery, String action) {
        return new GuiActionStep("control_window", null, null, null, null, null, null, null, windowQuery, action, null, null, null, null, null, null, null, null);
    }

    public static GuiActionStep clickCoordinates(int normX, int normY, String button, int clickCount) {
        return new GuiActionStep("click_coordinates", normX, normY, null, null, null, button, clickCount, null, null, null, null, null, null, null, null, null, null);
    }

    public static GuiActionStep type(String text, boolean clearFirst, boolean pressEnter) {
        return new GuiActionStep("type", null, null, null, null, null, null, null, null, null, null, null, text, clearFirst, pressEnter, null, null, null);
    }

    public static GuiActionStep hotkey(String hotkey) {
        return new GuiActionStep("hotkey", null, null, null, null, null, null, null, null, null, null, null, null, null, null, hotkey, null, null);
    }
}
