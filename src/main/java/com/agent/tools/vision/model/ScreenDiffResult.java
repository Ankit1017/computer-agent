package com.agent.tools.vision.model;

import java.util.Locale;

/**
 * Immutable record capturing the visual difference between two desktop frames.
 *
 * @param changedPixelPercent Percentage of pixels that changed (0.0 to 100.0)
 * @param changedPixelCount Total count of pixels that changed
 * @param totalPixels Total pixels evaluated in the comparison rectangle
 * @param visuallyChanged True if changedPixelPercent meets or exceeds significance threshold
 * @param minNormX Normalized [0..1000] horizontal minimum of the bounding box
 * @param minNormY Normalized [0..1000] vertical minimum of the bounding box
 * @param maxNormX Normalized [0..1000] horizontal maximum of the bounding box
 * @param maxNormY Normalized [0..1000] vertical maximum of the bounding box
 */
public record ScreenDiffResult(
        double changedPixelPercent,
        long changedPixelCount,
        long totalPixels,
        boolean visuallyChanged,
        int minNormX,
        int minNormY,
        int maxNormX,
        int maxNormY
) {

    public static ScreenDiffResult empty() {
        return new ScreenDiffResult(0.0, 0, 0, false, 0, 0, 0, 0);
    }

    /**
     * Produces a standardized verification summary string for appending to tool outputs.
     */
    public String toVerificationSummary() {
        if (visuallyChanged) {
            return String.format(Locale.US,
                    "[VISUAL VERIFICATION: Screen changed by %.2f%% (%d pixels) within normalized region [%d, %d -> %d, %d]]",
                    changedPixelPercent, changedPixelCount, minNormX, minNormY, maxNormX, maxNormY);
        } else {
            return String.format(Locale.US,
                    "[VISUAL WARNING: %.2f%% screen change detected after action — UI did not visibly react; target may be unfocused, disabled, or off-target]",
                    changedPixelPercent);
        }
    }
}
