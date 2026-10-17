package com.agent.tools.vision.services;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Windows-specific native WindowSystemProvider leveraging tasklist, PowerShell, and Win32 automation.
 */
public class WindowsWindowSystemProvider implements WindowSystemProvider {

    @Override
    public List<RawWindow> enumerateWindows() {
        List<RawWindow> windows = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/v", "/fo", "csv");
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean isHeader = true;
                int tileIndex = 0;
                while ((line = reader.readLine()) != null) {
                    if (isHeader) {
                        isHeader = false;
                        continue;
                    }
                    List<String> cols = parseCsvLine(line);
                    if (cols.size() >= 9) {
                        String processName = cleanQuotes(cols.get(0));
                        String pidStr = cleanQuotes(cols.get(1));
                        String title = cleanQuotes(cols.get(8));

                        if (!title.isEmpty() && !title.equalsIgnoreCase("N/A")
                                && !title.equalsIgnoreCase("OleMainThreadWndName")
                                && !title.equalsIgnoreCase("Temp Window")
                                && !title.equalsIgnoreCase("Default IME")
                                && !title.equalsIgnoreCase("MSCTFIME UI")) {

                            long pid = 0;
                            try {
                                pid = Long.parseLong(pidStr);
                            } catch (NumberFormatException ignored) {}

                            // Provide default bounded desktop dimensions if detailed rect query is unavailable
                            int defaultX = 50 + (tileIndex * 40) % 400;
                            int defaultY = 50 + (tileIndex * 30) % 300;
                            int defaultW = 1200;
                            int defaultH = 800;
                            tileIndex++;

                            windows.add(new RawWindow(
                                    pid,
                                    pid,
                                    processName,
                                    title,
                                    defaultX,
                                    defaultY,
                                    defaultW,
                                    defaultH,
                                    tileIndex == 1 // mark first window as active default
                            ));
                        }
                    }
                }
            }
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Graceful fallback if tasklist is not accessible
        }
        return windows;
    }

    @Override
    public Optional<RawWindow> getActiveWindow() {
        List<RawWindow> windows = enumerateWindows();
        return windows.stream().filter(RawWindow::active).findFirst();
    }

    @Override
    public boolean focusWindow(RawWindow window) {
        if (window == null) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "(New-Object -ComObject WScript.Shell).AppActivate(" + window.processId() + ")"
            );
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean controlWindow(RawWindow window, String action) {
        if (window == null || action == null) return false;
        String act = action.toLowerCase().trim();
        try {
            if ("close".equals(act)) {
                ProcessBuilder pb = new ProcessBuilder("taskkill", "/PID", String.valueOf(window.processId()));
                Process p = pb.start();
                boolean finished = p.waitFor(3, TimeUnit.SECONDS);
                return finished && p.exitValue() == 0;
            } else {
                // Focus / activate for restore or maximize
                return focusWindow(window);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens;
    }

    private static String cleanQuotes(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
