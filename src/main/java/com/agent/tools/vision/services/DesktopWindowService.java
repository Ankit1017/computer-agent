package com.agent.tools.vision.services;

import com.agent.tools.vision.model.WindowInfo;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enterprise Desktop Window and Application Management service.
 * Enumerate visible desktop windows, map coordinates, focus, control window states, and launch applications.
 */
public class DesktopWindowService {

    private final CoordinateConverter coordinateConverter;
    private final WindowSystemProvider provider;

    public DesktopWindowService() {
        this(new CoordinateConverter(new Dimension(1920, 1080)), createDefaultProvider());
    }

    public DesktopWindowService(CoordinateConverter coordinateConverter) {
        this(coordinateConverter, createDefaultProvider());
    }

    public DesktopWindowService(CoordinateConverter coordinateConverter, WindowSystemProvider provider) {
        this.coordinateConverter = (coordinateConverter != null) ? coordinateConverter : new CoordinateConverter(new Dimension(1920, 1080));
        this.provider = (provider != null) ? provider : createDefaultProvider();
    }

    public static WindowSystemProvider createDefaultProvider() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new WindowsWindowSystemProvider();
        } else {
            return new FallbackWindowSystemProvider();
        }
    }

    /**
     * Enumerates visible desktop windows, mapping physical pixel rectangles into normalized [0..1000] space.
     */
    public List<WindowInfo> listVisibleWindows() {
        List<WindowSystemProvider.RawWindow> rawWindows = provider.enumerateWindows();
        List<WindowInfo> result = new ArrayList<>();
        int markId = 1;
        for (WindowSystemProvider.RawWindow raw : rawWindows) {
            int normX = coordinateConverter.pixelToNormalizedX(raw.x());
            int normY = coordinateConverter.pixelToNormalizedY(raw.y());
            int endPixelX = raw.x() + raw.width();
            int endPixelY = raw.y() + raw.height();
            int normWidth = Math.max(1, coordinateConverter.pixelToNormalizedX(endPixelX) - normX);
            int normHeight = Math.max(1, coordinateConverter.pixelToNormalizedY(endPixelY) - normY);

            result.add(new WindowInfo(
                    markId++,
                    raw.processId(),
                    raw.processName(),
                    raw.title(),
                    raw.x(),
                    raw.y(),
                    raw.width(),
                    raw.height(),
                    normX,
                    normY,
                    normWidth,
                    normHeight,
                    raw.active()
            ));
        }
        return result;
    }

    /**
     * Returns the currently focused foreground window.
     */
    public Optional<WindowInfo> getActiveWindow() {
        List<WindowInfo> windows = listVisibleWindows();
        for (WindowInfo win : windows) {
            if (win.active()) {
                return Optional.of(win);
            }
        }
        return provider.getActiveWindow().map(raw -> {
            int normX = coordinateConverter.pixelToNormalizedX(raw.x());
            int normY = coordinateConverter.pixelToNormalizedY(raw.y());
            int normWidth = Math.max(1, coordinateConverter.pixelToNormalizedX(raw.x() + raw.width()) - normX);
            int normHeight = Math.max(1, coordinateConverter.pixelToNormalizedY(raw.y() + raw.height()) - normY);
            return new WindowInfo(
                    1,
                    raw.processId(),
                    raw.processName(),
                    raw.title(),
                    raw.x(),
                    raw.y(),
                    raw.width(),
                    raw.height(),
                    normX,
                    normY,
                    normWidth,
                    normHeight,
                    true
            );
        });
    }

    /**
     * Locates a window by case-insensitive substring on title, process name, or mark tag (e.g. "[W1]", "1").
     */
    public Optional<WindowInfo> findWindow(String titleOrProcessQuery) {
        if (titleOrProcessQuery == null || titleOrProcessQuery.isBlank()) {
            return Optional.empty();
        }
        String q = titleOrProcessQuery.trim().toLowerCase();
        List<WindowInfo> windows = listVisibleWindows();

        // 1. Tag lookup: e.g. "[W1]", "W1", "1"
        String numericPart = q.replaceAll("[\\[\\]wW]", "").trim();
        if (!numericPart.isEmpty() && numericPart.matches("\\d+")) {
            try {
                int targetId = Integer.parseInt(numericPart);
                for (WindowInfo win : windows) {
                    if (win.markId() == targetId) {
                        return Optional.of(win);
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        // 2. Exact match on title or process
        for (WindowInfo win : windows) {
            if ((win.title() != null && win.title().equalsIgnoreCase(q)) ||
                (win.processName() != null && win.processName().equalsIgnoreCase(q))) {
                return Optional.of(win);
            }
        }

        // 3. Substring match (prefer active window if both match)
        WindowInfo bestMatch = null;
        for (WindowInfo win : windows) {
            boolean match = (win.title() != null && win.title().toLowerCase().contains(q)) ||
                            (win.processName() != null && win.processName().toLowerCase().contains(q));
            if (match) {
                if (win.active()) {
                    return Optional.of(win);
                }
                if (bestMatch == null) {
                    bestMatch = win;
                }
            }
        }
        return Optional.ofNullable(bestMatch);
    }

    /**
     * Brings the matching window to the foreground and restores it.
     */
    public String focusWindow(String titleOrProcessQuery) {
        if (titleOrProcessQuery == null || titleOrProcessQuery.isBlank()) {
            return "Error: Window query cannot be empty.";
        }
        Optional<WindowInfo> opt = findWindow(titleOrProcessQuery);
        if (opt.isEmpty()) {
            return "No visible window found matching query: '" + titleOrProcessQuery + "'. Use listOpenWindows to see available windows.";
        }
        WindowInfo win = opt.get();
        WindowSystemProvider.RawWindow raw = new WindowSystemProvider.RawWindow(
                0L,
                win.processId(),
                win.processName(),
                win.title(),
                win.x(),
                win.y(),
                win.width(),
                win.height(),
                win.active()
        );
        boolean success = provider.focusWindow(raw);
        if (success) {
            return "Focused window [W" + win.markId() + "]: '" + win.title() + "' (Process: " + win.processName() + ", PID: " + win.processId() + ", Bounds: [" + win.normX() + ", " + win.normY() + ", " + win.normWidth() + "x" + win.normHeight() + "]).";
        } else {
            return "Failed to focus window: '" + win.title() + "'.";
        }
    }

    /**
     * Executes a window state control action: "maximize", "minimize", "restore", or "close".
     */
    public String controlWindow(String titleOrProcessQuery, String action) {
        if (titleOrProcessQuery == null || titleOrProcessQuery.isBlank()) {
            return "Error: Window query cannot be empty.";
        }
        if (action == null || action.isBlank()) {
            return "Error: Window control action cannot be empty.";
        }
        String act = action.trim().toLowerCase();
        if (!List.of("maximize", "minimize", "restore", "close").contains(act)) {
            return "Error: Invalid window control action '" + action + "'. Supported actions: maximize, minimize, restore, close.";
        }
        Optional<WindowInfo> opt = findWindow(titleOrProcessQuery);
        if (opt.isEmpty()) {
            return "No visible window found matching query: '" + titleOrProcessQuery + "'. Use listOpenWindows to see available windows.";
        }
        WindowInfo win = opt.get();
        WindowSystemProvider.RawWindow raw = new WindowSystemProvider.RawWindow(
                0L,
                win.processId(),
                win.processName(),
                win.title(),
                win.x(),
                win.y(),
                win.width(),
                win.height(),
                win.active()
        );
        boolean success = provider.controlWindow(raw, act);
        if (success) {
            return "Window [W" + win.markId() + "] ('" + win.title() + "') action '" + act + "' executed successfully.";
        } else {
            return "Failed to execute '" + act + "' on window: '" + win.title() + "'.";
        }
    }

    /**
     * Launches a desktop application or executable non-blockingly.
     */
    public String launchApplication(String application, String arguments) {
        if (application == null || application.isBlank()) {
            return "Error: Application name or path cannot be empty.";
        }
        String app = application.trim();
        if (app.contains("&") || app.contains("|") || app.contains(";") || app.contains("\n") || app.contains("\r")) {
            return "Error: Application command contains illegal shell control characters.";
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(app);
            if (arguments != null && !arguments.isBlank()) {
                String[] args = arguments.trim().split("\\s+");
                for (String a : args) {
                    if (!a.isBlank()) {
                        command.add(a);
                    }
                }
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();
            long pid = process.pid();
            return "Application '" + app + "' launched successfully (PID: " + pid + ").";
        } catch (Exception e) {
            return "Error launching application '" + app + "': " + e.getMessage();
        }
    }

    public CoordinateConverter getCoordinateConverter() {
        return coordinateConverter;
    }

    public WindowSystemProvider getProvider() {
        return provider;
    }
}
