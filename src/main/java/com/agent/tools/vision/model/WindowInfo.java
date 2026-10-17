package com.agent.tools.vision.model;

/**
 * Immutable record representing a visible desktop window with physical pixel and normalized [0..1000] bounds.
 *
 * @param markId 1-based sequential tag (e.g. 1 for [W1], 2 for [W2])
 * @param processId Native OS process ID
 * @param processName Process executable name
 * @param title Window title text
 * @param x Physical pixel horizontal origin
 * @param y Physical pixel vertical origin
 * @param width Physical pixel width
 * @param height Physical pixel height
 * @param normX Normalized [0..1000] horizontal origin
 * @param normY Normalized [0..1000] vertical origin
 * @param normWidth Normalized [0..1000] width
 * @param normHeight Normalized [0..1000] height
 * @param active Whether this window is currently in the foreground
 */
public record WindowInfo(
        int markId,
        long processId,
        String processName,
        String title,
        int x,
        int y,
        int width,
        int height,
        int normX,
        int normY,
        int normWidth,
        int normHeight,
        boolean active
) {

    public String tag() {
        return "[W" + markId + "]";
    }

    /**
     * Calculates the normalized [0..1000] horizontal center point of the window.
     */
    public int centerNormX() {
        return Math.max(0, Math.min(1000, normX + normWidth / 2));
    }

    /**
     * Calculates the normalized [0..1000] vertical center point of the window.
     */
    public int centerNormY() {
        return Math.max(0, Math.min(1000, normY + normHeight / 2));
    }

    /**
     * Calculates the normalized [0..1000] horizontal screen coordinate for a relative offset within the window.
     *
     * @param percentX Relative offset from 0.0 (left edge) to 1.0 (right edge)
     * @return Normalized screen X coordinate [0..1000]
     */
    public int relativePointNormX(double percentX) {
        double clamped = Math.max(0.0, Math.min(1.0, percentX));
        return Math.max(0, Math.min(1000, normX + (int) Math.round(clamped * normWidth)));
    }

    /**
     * Calculates the normalized [0..1000] vertical screen coordinate for a relative offset within the window.
     *
     * @param percentY Relative offset from 0.0 (top edge) to 1.0 (bottom edge)
     * @return Normalized screen Y coordinate [0..1000]
     */
    public int relativePointNormY(double percentY) {
        double clamped = Math.max(0.0, Math.min(1.0, percentY));
        return Math.max(0, Math.min(1000, normY + (int) Math.round(clamped * normHeight)));
    }
}
