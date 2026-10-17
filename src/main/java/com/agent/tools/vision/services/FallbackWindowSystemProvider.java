package com.agent.tools.vision.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Fallback WindowSystemProvider for Linux/macOS or headless test environments.
 */
public class FallbackWindowSystemProvider implements WindowSystemProvider {

    @Override
    public List<RawWindow> enumerateWindows() {
        List<RawWindow> windows = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("linux")) {
                // Try wmctrl -l -G -p
                ProcessBuilder pb = new ProcessBuilder("wmctrl", "-l", "-G", "-p");
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.trim().split("\\s+", 9);
                        if (parts.length >= 9) {
                            long handle = parseHex(parts[0]);
                            long pid = parseLong(parts[2]);
                            int x = parseInt(parts[3]);
                            int y = parseInt(parts[4]);
                            int w = parseInt(parts[5]);
                            int h = parseInt(parts[6]);
                            String title = parts[8];
                            windows.add(new RawWindow(handle, pid, "linux-app", title, x, y, w, h, windows.isEmpty()));
                        }
                    }
                }
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // Safe fallback: tool absent or headless
        }
        return windows;
    }

    @Override
    public Optional<RawWindow> getActiveWindow() {
        return enumerateWindows().stream().filter(RawWindow::active).findFirst();
    }

    @Override
    public boolean focusWindow(RawWindow window) {
        if (window == null) return false;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("linux")) {
                ProcessBuilder pb = new ProcessBuilder("wmctrl", "-i", "-a", "0x" + Long.toHexString(window.windowHandle()));
                Process p = pb.start();
                return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public boolean controlWindow(RawWindow window, String action) {
        if (window == null || action == null) return false;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("linux") && "close".equalsIgnoreCase(action)) {
                ProcessBuilder pb = new ProcessBuilder("wmctrl", "-i", "-c", "0x" + Long.toHexString(window.windowHandle()));
                Process p = pb.start();
                return p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static long parseHex(String hex) {
        try {
            if (hex.startsWith("0x") || hex.startsWith("0X")) {
                return Long.parseLong(hex.substring(2), 16);
            }
            return Long.parseLong(hex, 16);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseLong(String val) {
        try {
            return Long.parseLong(val);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int parseInt(String val) {
        try {
            return Integer.parseInt(val);
        } catch (Exception e) {
            return 0;
        }
    }
}
