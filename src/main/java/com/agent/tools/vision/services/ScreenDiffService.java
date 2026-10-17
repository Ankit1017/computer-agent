package com.agent.tools.vision.services;

import com.agent.tools.vision.model.ScreenDiffResult;

import java.awt.image.BufferedImage;
import java.util.function.Supplier;

/**
 * Fast, zero-dependency visual state difference and screen settle analysis service.
 */
public class ScreenDiffService {

    public static final double DEFAULT_CHANGE_THRESHOLD_PERCENT = 0.05;
    public static final int DEFAULT_RGB_NOISE_TOLERANCE = 15;

    private final double changeThresholdPercent;
    private final int rgbNoiseTolerance;

    public ScreenDiffService() {
        this(DEFAULT_CHANGE_THRESHOLD_PERCENT, DEFAULT_RGB_NOISE_TOLERANCE);
    }

    public ScreenDiffService(double changeThresholdPercent, int rgbNoiseTolerance) {
        this.changeThresholdPercent = (changeThresholdPercent >= 0.0) ? changeThresholdPercent : DEFAULT_CHANGE_THRESHOLD_PERCENT;
        this.rgbNoiseTolerance = (rgbNoiseTolerance >= 0) ? rgbNoiseTolerance : DEFAULT_RGB_NOISE_TOLERANCE;
    }

    /**
     * Compares two desktop frames and calculates changed pixels, percentage, and normalized bounding box.
     */
    public ScreenDiffResult compare(BufferedImage before, BufferedImage after, CoordinateConverter converter) {
        return compare(before, after, converter, this.changeThresholdPercent, this.rgbNoiseTolerance);
    }

    /**
     * Compares two frames with custom significance threshold and per-channel noise tolerance.
     */
    public ScreenDiffResult compare(
            BufferedImage before,
            BufferedImage after,
            CoordinateConverter converter,
            double thresholdPercent,
            int rgbTolerance) {
        if (before == null || after == null) {
            return ScreenDiffResult.empty();
        }

        int width = Math.min(before.getWidth(), after.getWidth());
        int height = Math.min(before.getHeight(), after.getHeight());
        long totalPixels = (long) width * (long) height;

        if (totalPixels <= 0) {
            return ScreenDiffResult.empty();
        }

        long changedCount = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb1 = before.getRGB(x, y);
                int rgb2 = after.getRGB(x, y);

                if (rgb1 != rgb2) {
                    int r1 = (rgb1 >> 16) & 0xFF;
                    int g1 = (rgb1 >> 8) & 0xFF;
                    int b1 = rgb1 & 0xFF;

                    int r2 = (rgb2 >> 16) & 0xFF;
                    int g2 = (rgb2 >> 8) & 0xFF;
                    int b2 = rgb2 & 0xFF;

                    if (Math.abs(r1 - r2) > rgbTolerance ||
                        Math.abs(g1 - g2) > rgbTolerance ||
                        Math.abs(b1 - b2) > rgbTolerance) {
                        changedCount++;
                        if (x < minX) minX = x;
                        if (y < minY) minY = y;
                        if (x > maxX) maxX = x;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        }

        int minNormX = 0;
        int minNormY = 0;
        int maxNormX = 0;
        int maxNormY = 0;

        if (changedCount > 0) {
            if (converter != null) {
                minNormX = converter.pixelToNormalizedX(minX);
                minNormY = converter.pixelToNormalizedY(minY);
                maxNormX = converter.pixelToNormalizedX(maxX);
                maxNormY = converter.pixelToNormalizedY(maxY);
            } else {
                minNormX = (int) Math.round(((double) minX / width) * 1000.0);
                minNormY = (int) Math.round(((double) minY / height) * 1000.0);
                maxNormX = (int) Math.round(((double) maxX / width) * 1000.0);
                maxNormY = (int) Math.round(((double) maxY / height) * 1000.0);
            }
        }

        double changedPercent = ((double) changedCount / (double) totalPixels) * 100.0;
        boolean visuallyChanged = changedPercent >= thresholdPercent;

        return new ScreenDiffResult(
                changedPercent,
                changedCount,
                totalPixels,
                visuallyChanged,
                minNormX,
                minNormY,
                maxNormX,
                maxNormY
        );
    }

    /**
     * Polls frames until two consecutive frames show negligible change or maxWaitMs expires.
     */
    public ScreenDiffResult waitForScreenSettle(
            Supplier<BufferedImage> frameSupplier,
            CoordinateConverter converter,
            int maxWaitMs,
            int pollIntervalMs) {
        if (frameSupplier == null) {
            return ScreenDiffResult.empty();
        }

        int clampedPoll = Math.max(50, Math.min(500, pollIntervalMs));
        int clampedMaxWait = Math.max(100, Math.min(10000, maxWaitMs));

        BufferedImage initial = frameSupplier.get();
        if (initial == null) {
            return ScreenDiffResult.empty();
        }

        BufferedImage prev = initial;
        BufferedImage current = initial;
        long start = System.currentTimeMillis();

        while (System.currentTimeMillis() - start < clampedMaxWait) {
            try {
                Thread.sleep(clampedPoll);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            BufferedImage next = frameSupplier.get();
            if (next == null) {
                break;
            }
            current = next;

            ScreenDiffResult stepDiff = compare(prev, next, converter);
            if (!stepDiff.visuallyChanged() || stepDiff.changedPixelPercent() < changeThresholdPercent) {
                // UI has stabilized between consecutive frames
                break;
            }
            prev = next;
        }

        return compare(initial, current, converter);
    }
}
