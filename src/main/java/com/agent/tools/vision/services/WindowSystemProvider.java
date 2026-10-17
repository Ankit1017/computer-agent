package com.agent.tools.vision.services;

import java.util.List;
import java.util.Optional;

/**
 * Pluggable provider interface abstracting OS-level window management operations.
 */
public interface WindowSystemProvider {

    /**
     * Raw descriptor of a window before coordinate normalization.
     */
    record RawWindow(
            long windowHandle,
            long processId,
            String processName,
            String title,
            int x,
            int y,
            int width,
            int height,
            boolean active
    ) {}

    /**
     * Enumerates currently visible top-level desktop windows.
     */
    List<RawWindow> enumerateWindows();

    /**
     * Returns the currently focused foreground window, if detectable.
     */
    Optional<RawWindow> getActiveWindow();

    /**
     * Brings the specified window to the foreground, restoring it if minimized.
     */
    boolean focusWindow(RawWindow window);

    /**
     * Executes a window state control action ("maximize", "minimize", "restore", "close").
     */
    boolean controlWindow(RawWindow window, String action);
}
