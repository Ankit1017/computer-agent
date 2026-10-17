package com.agent.tools.vision.hud;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Non-blocking, translucent visual HUD status bar overlay for live desktop CUA tracking.
 * Safe for headless CI/testing environments.
 */
public class CuaHudOverlay implements AutoCloseable {

    private volatile boolean enabled = false;
    private volatile String lastAction = "IDLE";
    private volatile String lastTargetCoord = "N/A";
    private volatile String lastVerificationStatus = "N/A";
    private final AtomicInteger updateCount = new AtomicInteger(0);

    private JWindow hudWindow;
    private JLabel actionLabel;
    private JLabel statusLabel;

    public CuaHudOverlay() {
        this(false);
    }

    public CuaHudOverlay(boolean startEnabled) {
        setEnabled(startEnabled);
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        if (enabled) {
            initOrShowHud();
        } else {
            hideOrDisposeHud();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Records a CUA action and verification status in real time.
     * Updates in-memory state unconditionally (allowing headless verification),
     * and repaints the graphical HUD if enabled.
     */
    public void recordAction(String actionName, String targetSummary, String verificationSummary) {
        this.lastAction = (actionName != null && !actionName.isBlank()) ? actionName : "UNKNOWN";
        this.lastTargetCoord = (targetSummary != null && !targetSummary.isBlank()) ? targetSummary : "N/A";
        this.lastVerificationStatus = (verificationSummary != null && !verificationSummary.isBlank()) ? verificationSummary : "N/A";
        this.updateCount.incrementAndGet();

        if (enabled && !GraphicsEnvironment.isHeadless() && hudWindow != null) {
            SwingUtilities.invokeLater(this::updateHudComponents);
        }
    }

    private void initOrShowHud() {
        try {
            if (hudWindow == null) {
                hudWindow = new JWindow();
                hudWindow.setAlwaysOnTop(true);
                hudWindow.setFocusableWindowState(false);
                hudWindow.setBackground(new Color(0, 0, 0, 0)); // Transparent window background

                JPanel panel = new JPanel() {
                    @Override
                    protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(new Color(25, 25, 30, 220));
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                        g2.setColor(new Color(80, 160, 255, 180));
                        g2.setStroke(new BasicStroke(1.5f));
                        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                        g2.dispose();
                    }
                };
                panel.setLayout(new BorderLayout(8, 4));
                panel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
                panel.setOpaque(false);

                actionLabel = new JLabel("[CUA HUD] " + lastAction + " | " + lastTargetCoord);
                actionLabel.setFont(new Font("Segoe UI", Font.BOLD, 12));
                actionLabel.setForeground(Color.WHITE);

                statusLabel = new JLabel(formatStatusDisplay(lastVerificationStatus));
                statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
                statusLabel.setForeground(getStatusColor(lastVerificationStatus));

                panel.add(actionLabel, BorderLayout.NORTH);
                panel.add(statusLabel, BorderLayout.SOUTH);

                hudWindow.getContentPane().add(panel);
                hudWindow.setSize(380, 52);

                // Position top-right of primary screen
                Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
                int x = scr.width - 400;
                int y = 20;
                hudWindow.setLocation(x, y);
            }
            updateHudComponents();
            hudWindow.setVisible(true);
        } catch (Exception ignored) {
            // Failsafe against platform-specific display server quirks
        }
    }

    private void hideOrDisposeHud() {
        try {
            if (hudWindow != null) {
                hudWindow.setVisible(false);
                hudWindow.dispose();
                hudWindow = null;
                actionLabel = null;
                statusLabel = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void updateHudComponents() {
        if (actionLabel != null && statusLabel != null) {
            actionLabel.setText("[CUA HUD] " + lastAction + " | " + lastTargetCoord);
            statusLabel.setText(formatStatusDisplay(lastVerificationStatus));
            statusLabel.setForeground(getStatusColor(lastVerificationStatus));
            if (hudWindow != null) {
                hudWindow.repaint();
            }
        }
    }

    private String formatStatusDisplay(String verification) {
        if (verification == null || verification.isBlank() || "N/A".equals(verification)) {
            return "Visual Status: READY";
        }
        if (verification.contains("VISUAL VERIFICATION:")) {
            return "VERIFIED " + extractPercentage(verification);
        }
        if (verification.contains("VISUAL WARNING:")) {
            return "NO-OP WARNING " + extractPercentage(verification);
        }
        return verification;
    }

    private String extractPercentage(String text) {
        int idx = text.indexOf("%");
        if (idx > 0) {
            int start = Math.max(0, text.lastIndexOf(" ", idx) + 1);
            return "(" + text.substring(start, idx + 1) + ")";
        }
        return "";
    }

    private Color getStatusColor(String verification) {
        if (verification == null) return Color.LIGHT_GRAY;
        if (verification.contains("VISUAL VERIFICATION:")) return new Color(100, 255, 100);
        if (verification.contains("VISUAL WARNING:")) return new Color(255, 180, 70);
        return Color.LIGHT_GRAY;
    }

    public String getLastAction() {
        return lastAction;
    }

    public String getLastTargetCoord() {
        return lastTargetCoord;
    }

    public String getLastVerificationStatus() {
        return lastVerificationStatus;
    }

    public int getUpdateCount() {
        return updateCount.get();
    }

    public String getStatusSnapshot() {
        return String.format("CUA HUD [enabled=%b, updates=%d, lastAction=%s, lastTarget=%s, lastVerification=%s]",
                enabled, updateCount.get(), lastAction, lastTargetCoord, lastVerificationStatus);
    }

    @Override
    public void close() {
        setEnabled(false);
    }
}
