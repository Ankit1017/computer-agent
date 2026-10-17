package com.agent.tools.vision.services;

import java.awt.Dimension;

/**
 * Deterministic coordinate engine mapping between standard Computer-Use normalized [0..1000]
 * coordinate space and physical screen pixels.
 */
public class CoordinateConverter {

    private final Dimension screenSize;

    public CoordinateConverter(Dimension screenSize) {
        this.screenSize = screenSize != null ? screenSize : new Dimension(1920, 1080);
    }

    /**
     * Converts a normalized horizontal coordinate [0..1000] to a physical screen pixel X coordinate.
     */
    public int normalizedToPixelX(int normX) {
        int clamped = Math.max(0, Math.min(1000, normX));
        if (screenSize.width <= 0) {
            return 0;
        }
        return (int) Math.min(Math.max(0, screenSize.width - 1), Math.round((clamped / 1000.0) * screenSize.width));
    }

    /**
     * Converts a normalized vertical coordinate [0..1000] to a physical screen pixel Y coordinate.
     */
    public int normalizedToPixelY(int normY) {
        int clamped = Math.max(0, Math.min(1000, normY));
        if (screenSize.height <= 0) {
            return 0;
        }
        return (int) Math.min(Math.max(0, screenSize.height - 1), Math.round((clamped / 1000.0) * screenSize.height));
    }

    /**
     * Converts a physical screen pixel X coordinate to normalized [0..1000] coordinate space.
     */
    public int pixelToNormalizedX(int pixelX) {
        if (screenSize.width <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(screenSize.width, pixelX));
        return (int) Math.min(1000, Math.round((clamped / (double) screenSize.width) * 1000.0));
    }

    /**
     * Converts a physical screen pixel Y coordinate to normalized [0..1000] coordinate space.
     */
    public int pixelToNormalizedY(int pixelY) {
        if (screenSize.height <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(screenSize.height, pixelY));
        return (int) Math.min(1000, Math.round((clamped / (double) screenSize.height) * 1000.0));
    }

    public Dimension getScreenSize() {
        return screenSize;
    }
}
