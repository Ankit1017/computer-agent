package com.agent.tools.vision;

import com.agent.guardrails.UntrustedContentSanitizer;
import com.agent.telemetry.SwarmTelemetry;
import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.vision.services.CoordinateConverter;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;

public class VisionToolsFacade {
    private final SecurityInterceptor security;
    private final Robot robot;
    private final ChatLanguageModel visionModel;
    private final Dimension screenSize;
    private final java.awt.datatransfer.Clipboard clipboard;
    private final CoordinateConverter coordinateConverter;
    private static final int GRID_COLS = 10;
    private static final int GRID_ROWS = 10;

    public VisionToolsFacade(SecurityInterceptor security) {
        this.security = security;
        try {
            this.robot = new Robot();
            this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        } catch (AWTException e) {
            throw new RuntimeException("Failed to initialize java.awt.Robot", e);
        }

        // Instantiate a dedicated model just for vision tasks with maxRetries 1
        this.visionModel = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("llama3.2-vision")
                .temperature(0.0)
                .timeout(Duration.ofMinutes(2))
                .maxRetries(1)
                .build();
        this.clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        this.coordinateConverter = new CoordinateConverter(this.screenSize);
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize) {
        this(security, robot, visionModel, screenSize, Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize, java.awt.datatransfer.Clipboard clipboard) {
        this.security = security;
        this.robot = robot;
        this.visionModel = visionModel;
        this.screenSize = screenSize != null ? screenSize : new Dimension(1920, 1080);
        this.clipboard = clipboard != null ? clipboard : Toolkit.getDefaultToolkit().getSystemClipboard();
        this.coordinateConverter = new CoordinateConverter(this.screenSize);
    }

    public int normalizedToPixelX(int normX) {
        return coordinateConverter.normalizedToPixelX(normX);
    }

    public int normalizedToPixelY(int normY) {
        return coordinateConverter.normalizedToPixelY(normY);
    }

    public int pixelToNormalizedX(int pixelX) {
        return coordinateConverter.pixelToNormalizedX(pixelX);
    }

    public int pixelToNormalizedY(int pixelY) {
        return coordinateConverter.pixelToNormalizedY(pixelY);
    }

    public Dimension getScreenSize() {
        return screenSize;
    }

    @Tool("Capture the screen. The image is pre-processed with a Set-of-Marks grid (IDs 0 to 99). Ask the model what is located at a specific ID, or ask it to find the ID of a target application.")
    public String inspectScreen(@P("The question to ask the vision model about the screen.") String question) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("inspectScreen",
                com.agent.telemetry.SwarmTelemetry.formatArgs("question", question),
                () -> {
                    try {
                        // 1. Capture screen
                        Rectangle screenRect = new Rectangle(screenSize);
                        BufferedImage capture = robot.createScreenCapture(screenRect);

                        // 2. Apply Set-of-Marks Grid Overlay & Coordinate Ticks
                        Graphics2D g2d = capture.createGraphics();
                        g2d.setColor(Color.RED);
                        g2d.setFont(new Font("Arial", Font.BOLD, 16));
                        int cellWidth = screenSize.width / GRID_COLS;
                        int cellHeight = screenSize.height / GRID_ROWS;

                        int id = 0;
                        for (int row = 0; row < GRID_ROWS; row++) {
                            for (int col = 0; col < GRID_COLS; col++) {
                                int x = col * cellWidth;
                                int y = row * cellHeight;
                                g2d.drawRect(x, y, cellWidth, cellHeight);
                                // Draw ID in the center of the cell
                                g2d.drawString(String.valueOf(id), x + (cellWidth / 2) - 10, y + (cellHeight / 2) + 5);
                                id++;
                            }
                        }

                        // Border coordinate ticks (0, 200, 400, 600, 800, 1000)
                        g2d.setColor(Color.YELLOW);
                        g2d.setFont(new Font("Arial", Font.PLAIN, 11));
                        for (int tick = 0; tick <= 1000; tick += 200) {
                            int px = normalizedToPixelX(tick);
                            int py = normalizedToPixelY(tick);
                            // Top border tick
                            g2d.drawLine(px, 0, px, 8);
                            g2d.drawString(String.valueOf(tick), Math.min(px + 2, screenSize.width - 25), 10);
                            // Left border tick
                            g2d.drawLine(0, py, 8, py);
                            g2d.drawString(String.valueOf(tick), 10, Math.min(py + 10, screenSize.height - 5));
                        }

                        // Render cursor crosshair overlay if available
                        Point cursorLoc = null;
                        try {
                            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
                            if (pointerInfo != null) {
                                cursorLoc = pointerInfo.getLocation();
                            }
                        } catch (Exception ignored) {}

                        if (cursorLoc != null) {
                            int cx = cursorLoc.x;
                            int cy = cursorLoc.y;
                            g2d.setColor(Color.MAGENTA);
                            g2d.setStroke(new BasicStroke(2.0f));
                            g2d.drawOval(cx - 8, cy - 8, 16, 16);
                            g2d.drawLine(cx - 15, cy, cx + 15, cy);
                            g2d.drawLine(cx, cy - 15, cx, cy + 15);
                        }
                        g2d.dispose();

                        // 3. Convert to Base64 (PNG)
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(capture, "png", baos);
                        String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());

                        // 4. Construct LangChain4j multimodal message
                        String cursorMeta;
                        if (cursorLoc != null) {
                            int normX = pixelToNormalizedX(cursorLoc.x);
                            int normY = pixelToNormalizedY(cursorLoc.y);
                            cursorMeta = " (Current cursor position: Normalized [X=" + normX + ", Y=" + normY + "], Pixel [X=" + cursorLoc.x + ", Y=" + cursorLoc.y + "]).";
                        } else {
                            cursorMeta = " (Current cursor position: Unavailable/Headless).";
                        }

                        String prompt = (question != null ? question : "Describe the contents of the screen.")
                                + " (Note: The image has a 10x10 grid overlay. Reference the red ID numbers 0-99 to specify locations)."
                                + cursorMeta;
                        UserMessage msg = UserMessage.from(
                            TextContent.from(prompt),
                            ImageContent.from(base64Image, "image/png")
                        );

                        try {
                            return visionModel.generate(msg).content().text();
                        } catch (Exception modelEx) {
                            return "Vision Model Unavailable: Could not process image via Ollama (" + modelEx.getMessage() + "). Please ensure Ollama supports vision or navigate using keyboard hotkeys/coordinates.";
                        }
                    } catch (Exception e) {
                        return "Error capturing or analyzing screen: " + e.getMessage();
                    }
                });
    }

    @Tool("Capture and crop a specific high-resolution rectangular region of the screen using normalized coordinates [0..1000]. Overlays a fine 5x5 sub-grid (R0..R24) for pinpoint precision.")
    public String inspectScreenRegion(
            @P("Start horizontal coordinate [0..1000] (left)") Integer startNormX,
            @P("Start vertical coordinate [0..1000] (top)") Integer startNormY,
            @P("End horizontal coordinate [0..1000] (right)") Integer endNormX,
            @P("End vertical coordinate [0..1000] (bottom)") Integer endNormY,
            @P("Question or inspection prompt for the vision model") String question) {
        return SwarmTelemetry.executeTool("inspectScreenRegion",
                SwarmTelemetry.formatArgs("startNormX", startNormX, "startNormY", startNormY, "endNormX", endNormX, "endNormY", endNormY, "question", question),
                () -> {
                    if (startNormX == null || startNormY == null || endNormX == null || endNormY == null) {
                        return "Error: Coordinates cannot be null.";
                    }
                    if (startNormX < 0 || startNormY < 0 || endNormX > 1000 || endNormY > 1000 || startNormX >= endNormX || startNormY >= endNormY) {
                        return "Error: Invalid region coordinates. Must satisfy 0 <= startNormX < endNormX <= 1000 and 0 <= startNormY < endNormY <= 1000.";
                    }

                    try {
                        Rectangle screenRect = new Rectangle(screenSize);
                        BufferedImage fullCapture = robot.createScreenCapture(screenRect);

                        int startPixelX = normalizedToPixelX(startNormX);
                        int startPixelY = normalizedToPixelY(startNormY);
                        int endPixelX = normalizedToPixelX(endNormX);
                        int endPixelY = normalizedToPixelY(endNormY);
                        int width = Math.max(1, endPixelX - startPixelX);
                        int height = Math.max(1, endPixelY - startPixelY);

                        BufferedImage regionImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2d = regionImage.createGraphics();
                        g2d.drawImage(fullCapture, 0, 0, width, height, startPixelX, startPixelY, startPixelX + width, startPixelY + height, null);

                        // Overlay 5x5 sub-grid (R0..R24)
                        g2d.setColor(Color.CYAN);
                        int fontSize = Math.max(11, Math.min(width, height) / 16);
                        g2d.setFont(new Font("Arial", Font.BOLD, fontSize));
                        int subCellWidth = Math.max(1, width / 5);
                        int subCellHeight = Math.max(1, height / 5);
                        int subId = 0;
                        for (int r = 0; r < 5; r++) {
                            for (int c = 0; c < 5; c++) {
                                int x = c * subCellWidth;
                                int y = r * subCellHeight;
                                g2d.drawRect(x, y, subCellWidth, subCellHeight);
                                g2d.drawString("R" + subId, x + (subCellWidth / 2) - 10, y + (subCellHeight / 2) + 5);
                                subId++;
                            }
                        }
                        g2d.dispose();

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        ImageIO.write(regionImage, "png", baos);
                        String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());

                        String prompt = (question != null ? question : "Describe the contents of the cropped screen region.")
                                + " (Note: High-resolution zoom of normalized region [" + startNormX + ", " + startNormY + " to " + endNormX + ", " + endNormY + "] with 5x5 sub-grid R0 to R24).";
                        UserMessage msg = UserMessage.from(
                                TextContent.from(prompt),
                                ImageContent.from(base64Image, "image/png")
                        );

                        try {
                            return visionModel.generate(msg).content().text();
                        } catch (Exception modelEx) {
                            return "Vision Model Unavailable: Could not process image via Ollama (" + modelEx.getMessage() + "). Please ensure Ollama supports vision or navigate using keyboard hotkeys/coordinates.";
                        }
                    } catch (Exception e) {
                        return "Error inspecting screen region: " + e.getMessage();
                    }
                });
    }

    @Tool("Move the mouse to the center of a specific grid ID (0-99) and click.")
    public String clickGridId(@P("The numeric ID of the grid cell to click (0-99)") Integer gridId) {
        return com.agent.telemetry.SwarmTelemetry.executeTool("clickGridId",
                com.agent.telemetry.SwarmTelemetry.formatArgs("gridId", gridId),
                () -> {
                    if (gridId == null || gridId < 0 || gridId >= (GRID_COLS * GRID_ROWS)) {
                        return "Error: Invalid grid ID. Must be between 0 and 99.";
                    }

                    int cellWidth = screenSize.width / GRID_COLS;
                    int cellHeight = screenSize.height / GRID_ROWS;

                    int col = gridId % GRID_COLS;
                    int row = gridId / GRID_COLS;

                    // Calculate center of the cell
                    int targetX = (col * cellWidth) + (cellWidth / 2);
                    int targetY = (row * cellHeight) + (cellHeight / 2);

                    if (!security.requestPermission("click mouse at", "Grid ID:" + gridId + " (X:" + targetX + ", Y:" + targetY + ")")) {
                        return "ACTION REJECTED: User explicitly denied the mouse click. Do not retry this action.";
                    }

                    try {
                        robot.mouseMove(targetX, targetY);
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                        return "Clicked successfully at Grid ID " + gridId + ".";
                    } catch (Exception e) {
                        return "Error clicking mouse: " + e.getMessage();
                    }
                });
    }

    @Tool("Type text into the currently focused window using the clipboard.")
    public String typeText(@P("The text to type") String text) {
        return SwarmTelemetry.executeTool("typeText", SwarmTelemetry.formatArgs("text", text), () -> {
            String safeText = (text == null) ? "" : text;
            if (!security.requestPermission("type text", safeText)) {
                return "ACTION REJECTED: User explicitly denied typing text. Do not retry this action.";
            }
            try {
                StringSelection stringSelection = new StringSelection(safeText);
                int attempts = 0;
                while (true) {
                    try {
                        clipboard.setContents(stringSelection, null);
                        break;
                    } catch (IllegalStateException e) {
                        if (++attempts >= 5) {
                            throw e;
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
                int modifierKey = isMac ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;

                robot.keyPress(modifierKey);
                robot.keyPress(KeyEvent.VK_V);
                robot.keyRelease(KeyEvent.VK_V);
                robot.keyRelease(modifierKey);

                return "Text typed successfully.";
            } catch (Exception e) {
                return "Error typing text: " + e.getMessage();
            }
        });
    }

    @Tool("Click at a precise relative offset inside a grid cell (0-99). offsetPercentX and offsetPercentY range from 0.0 (top-left) to 1.0 (bottom-right). Center is 0.5, 0.5.")
    public String clickGridOffset(
            @P("Grid cell ID (0-99)") Integer gridId,
            @P("Horizontal offset within cell (0.0 to 1.0)") Double offsetPercentX,
            @P("Vertical offset within cell (0.0 to 1.0)") Double offsetPercentY) {
        return SwarmTelemetry.executeTool("clickGridOffset",
                SwarmTelemetry.formatArgs("gridId", gridId, "offsetPercentX", offsetPercentX, "offsetPercentY", offsetPercentY),
                () -> {
                    if (gridId == null || gridId < 0 || gridId >= (GRID_COLS * GRID_ROWS)) {
                        return "Error: Invalid grid ID. Must be between 0 and 99.";
                    }
                    double ox = (offsetPercentX != null) ? offsetPercentX : 0.5;
                    double oy = (offsetPercentY != null) ? offsetPercentY : 0.5;
                    double clampX = Math.max(0.0, Math.min(1.0, ox));
                    double clampY = Math.max(0.0, Math.min(1.0, oy));

                    int cellWidth = screenSize.width / GRID_COLS;
                    int cellHeight = screenSize.height / GRID_ROWS;
                    int col = gridId % GRID_COLS;
                    int row = gridId / GRID_COLS;

                    int targetX = (int) ((col * cellWidth) + (clampX * cellWidth));
                    int targetY = (int) ((row * cellHeight) + (clampY * cellHeight));

                    if (!security.requestPermission("precision click at", "Grid ID:" + gridId + " (X:" + targetX + ", Y:" + targetY + ")")) {
                        return "ACTION REJECTED: User explicitly denied the mouse click. Do not retry this action.";
                    }

                    try {
                        robot.mouseMove(targetX, targetY);
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                        return "Precision clicked at Grid " + gridId + " (" + targetX + ", " + targetY + ").";
                    } catch (Exception e) {
                        return "Error precision clicking: " + e.getMessage();
                    }
                });
    }

    @Tool("Double-click the mouse at the center of a grid ID (0-99).")
    public String doubleClickGridId(@P("Grid cell ID (0-99)") Integer gridId) {
        return SwarmTelemetry.executeTool("doubleClickGridId", SwarmTelemetry.formatArgs("gridId", gridId), () -> {
            if (gridId == null || gridId < 0 || gridId >= (GRID_COLS * GRID_ROWS)) {
                return "Error: Invalid grid ID. Must be between 0 and 99.";
            }
            int cellWidth = screenSize.width / GRID_COLS;
            int cellHeight = screenSize.height / GRID_ROWS;
            int targetX = (gridId % GRID_COLS) * cellWidth + (cellWidth / 2);
            int targetY = (gridId / GRID_COLS) * cellHeight + (cellHeight / 2);

            if (!security.requestPermission("double-click mouse at", "Grid ID:" + gridId + " (X:" + targetX + ", Y:" + targetY + ")")) {
                return "ACTION REJECTED: User explicitly denied the double click. Do not retry this action.";
            }

            try {
                robot.mouseMove(targetX, targetY);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.delay(100);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                return "Double-clicked at Grid ID " + gridId + ".";
            } catch (Exception e) {
                return "Error double clicking: " + e.getMessage();
            }
        });
    }

    @Tool("Right-click (context menu) at the center of a grid ID (0-99).")
    public String rightClickGridId(@P("Grid cell ID (0-99)") Integer gridId) {
        return SwarmTelemetry.executeTool("rightClickGridId", SwarmTelemetry.formatArgs("gridId", gridId), () -> {
            if (gridId == null || gridId < 0 || gridId >= (GRID_COLS * GRID_ROWS)) {
                return "Error: Invalid grid ID. Must be between 0 and 99.";
            }
            int cellWidth = screenSize.width / GRID_COLS;
            int cellHeight = screenSize.height / GRID_ROWS;
            int targetX = (gridId % GRID_COLS) * cellWidth + (cellWidth / 2);
            int targetY = (gridId / GRID_COLS) * cellHeight + (cellHeight / 2);

            if (!security.requestPermission("right-click mouse at", "Grid ID:" + gridId + " (X:" + targetX + ", Y:" + targetY + ")")) {
                return "ACTION REJECTED: User explicitly denied the right click. Do not retry this action.";
            }

            try {
                robot.mouseMove(targetX, targetY);
                robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
                return "Right-clicked at Grid ID " + gridId + ".";
            } catch (Exception e) {
                return "Error right clicking: " + e.getMessage();
            }
        });
    }

    @Tool("Scroll the mouse wheel. Positive notches scroll down; negative notches scroll up.")
    public String scrollMouse(@P("Number of scroll wheel notches (e.g., 5 for down, -5 for up)") Integer notches) {
        return SwarmTelemetry.executeTool("scrollMouse", SwarmTelemetry.formatArgs("notches", notches), () -> {
            int n = (notches != null) ? notches : 5;
            if (!security.requestPermission("scroll mouse wheel", n + " notches")) {
                return "ACTION REJECTED: User explicitly denied mouse scroll. Do not retry this action.";
            }
            try {
                robot.mouseWheel(n);
                return "Scrolled mouse " + n + " notches.";
            } catch (Exception e) {
                return "Error scrolling: " + e.getMessage();
            }
        });
    }

    @Tool("Trigger a key or combination hotkey (e.g., 'enter', 'esc', 'tab', 'ctrl+s', 'alt+f4', 'ctrl+shift+p').")
    public String pressHotkey(@P("Hotkey combination separated by '+'") String hotkey) {
        return SwarmTelemetry.executeTool("pressHotkey", SwarmTelemetry.formatArgs("hotkey", hotkey), () -> {
            if (hotkey == null || hotkey.isBlank()) return "Error: Hotkey string cannot be empty.";
            if (!security.requestPermission("press hotkey", hotkey)) {
                return "ACTION REJECTED: User explicitly denied hotkey press. Do not retry this action.";
            }

            String[] parts = hotkey.trim().toLowerCase().split("\\+");
            java.util.List<Integer> keyCodes = new java.util.ArrayList<>();

            for (String part : parts) {
                part = part.trim();
                switch (part) {
                    case "ctrl", "control" -> keyCodes.add(KeyEvent.VK_CONTROL);
                    case "alt" -> keyCodes.add(KeyEvent.VK_ALT);
                    case "shift" -> keyCodes.add(KeyEvent.VK_SHIFT);
                    case "win", "meta", "cmd" -> keyCodes.add(KeyEvent.VK_WINDOWS);
                    case "enter", "return" -> keyCodes.add(KeyEvent.VK_ENTER);
                    case "esc", "escape" -> keyCodes.add(KeyEvent.VK_ESCAPE);
                    case "tab" -> keyCodes.add(KeyEvent.VK_TAB);
                    case "backspace" -> keyCodes.add(KeyEvent.VK_BACK_SPACE);
                    case "space" -> keyCodes.add(KeyEvent.VK_SPACE);
                    case "up" -> keyCodes.add(KeyEvent.VK_UP);
                    case "down" -> keyCodes.add(KeyEvent.VK_DOWN);
                    case "left" -> keyCodes.add(KeyEvent.VK_LEFT);
                    case "right" -> keyCodes.add(KeyEvent.VK_RIGHT);
                    case "home" -> keyCodes.add(KeyEvent.VK_HOME);
                    case "end" -> keyCodes.add(KeyEvent.VK_END);
                    case "pageup", "pgup" -> keyCodes.add(KeyEvent.VK_PAGE_UP);
                    case "pagedown", "pgdn" -> keyCodes.add(KeyEvent.VK_PAGE_DOWN);
                    case "delete", "del" -> keyCodes.add(KeyEvent.VK_DELETE);
                    case "insert", "ins" -> keyCodes.add(KeyEvent.VK_INSERT);
                    default -> {
                        if (part.length() == 1) {
                            keyCodes.add(KeyEvent.getExtendedKeyCodeForChar(part.charAt(0)));
                        } else if (part.matches("f\\d{1,2}")) {
                            int fNum = Integer.parseInt(part.substring(1));
                            keyCodes.add(KeyEvent.VK_F1 + (fNum - 1));
                        } else {
                            return "Error: Unrecognized key '" + part + "'.";
                        }
                    }
                }
            }

            try {
                // Press keys sequentially
                for (int code : keyCodes) {
                    robot.keyPress(code);
                }
                robot.delay(50);
                return "Executed hotkey: " + hotkey;
            } catch (Exception e) {
                return "Error pressing hotkey: " + e.getMessage();
            } finally {
                // Guarantee key release in reverse order to avoid stuck system keys
                for (int i = keyCodes.size() - 1; i >= 0; i--) {
                    try {
                        robot.keyRelease(keyCodes.get(i));
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    @Tool("Get the current mouse cursor position in normalized [0..1000] and physical screen pixel coordinates.")
    public String getCursorPosition() {
        return SwarmTelemetry.executeTool("getCursorPosition", "", () -> {
            try {
                PointerInfo pointerInfo = MouseInfo.getPointerInfo();
                if (pointerInfo == null) {
                    return "Error: Mouse pointer info unavailable.";
                }
                Point p = pointerInfo.getLocation();
                int normX = pixelToNormalizedX(p.x);
                int normY = pixelToNormalizedY(p.y);
                return "Cursor position: Normalized (X: " + normX + ", Y: " + normY + "), Pixel (X: " + p.x + ", Y: " + p.y + ").";
            } catch (Exception e) {
                return "Error retrieving cursor position: " + e.getMessage();
            }
        });
    }

    @Tool("Move the mouse cursor smoothly to normalized coordinates [0..1000].")
    public String moveMouse(
            @P("Normalized horizontal coordinate [0..1000]") Integer normX,
            @P("Normalized vertical coordinate [0..1000]") Integer normY) {
        return SwarmTelemetry.executeTool("moveMouse",
                SwarmTelemetry.formatArgs("normX", normX, "normY", normY),
                () -> {
                    if (normX == null || normY == null || normX < 0 || normX > 1000 || normY < 0 || normY > 1000) {
                        return "Error: Coordinates must be between 0 and 1000.";
                    }
                    if (!security.requestPermission("move mouse to", "Normalized (X:" + normX + ", Y:" + normY + ")")) {
                        return "ACTION REJECTED: User explicitly denied mouse movement. Do not retry this action.";
                    }
                    try {
                        int targetX = normalizedToPixelX(normX);
                        int targetY = normalizedToPixelY(normY);
                        robot.mouseMove(targetX, targetY);
                        return "Mouse moved to normalized (" + normX + ", " + normY + ") [pixel: " + targetX + ", " + targetY + "].";
                    } catch (Exception e) {
                        return "Error moving mouse: " + e.getMessage();
                    }
                });
    }

    @Tool("Click the mouse at normalized coordinates [0..1000]. Supports 'left', 'right', 'middle' button and click count (1 for single click, 2 for double click).")
    public String clickCoordinates(
            @P("Normalized horizontal coordinate [0..1000]") Integer normX,
            @P("Normalized vertical coordinate [0..1000]") Integer normY,
            @P("Mouse button: 'left', 'right', or 'middle' (default: 'left')") String button,
            @P("Number of clicks: 1 for single click, 2 for double click (default: 1)") Integer clickCount) {
        return SwarmTelemetry.executeTool("clickCoordinates",
                SwarmTelemetry.formatArgs("normX", normX, "normY", normY, "button", button, "clickCount", clickCount),
                () -> {
                    if (normX == null || normY == null || normX < 0 || normX > 1000 || normY < 0 || normY > 1000) {
                        return "Error: Coordinates must be between 0 and 1000.";
                    }
                    String btn = (button != null && !button.isBlank()) ? button.trim().toLowerCase() : "left";
                    int count = (clickCount != null && clickCount > 0) ? Math.min(clickCount, 3) : 1;

                    int mask;
                    switch (btn) {
                        case "left" -> mask = InputEvent.BUTTON1_DOWN_MASK;
                        case "right" -> mask = InputEvent.BUTTON3_DOWN_MASK;
                        case "middle" -> mask = InputEvent.BUTTON2_DOWN_MASK;
                        default -> {
                            return "Error: Invalid mouse button '" + button + "'. Use 'left', 'right', or 'middle'.";
                        }
                    }

                    int targetX = normalizedToPixelX(normX);
                    int targetY = normalizedToPixelY(normY);

                    if (!security.requestPermission("click mouse at", "Normalized (X:" + normX + ", Y:" + normY + ") button=" + btn + " count=" + count)) {
                        return "ACTION REJECTED: User explicitly denied the mouse click. Do not retry this action.";
                    }

                    try {
                        robot.mouseMove(targetX, targetY);
                        for (int i = 0; i < count; i++) {
                            robot.mousePress(mask);
                            robot.mouseRelease(mask);
                            if (i < count - 1) {
                                robot.delay(100);
                            }
                        }
                        return "Clicked " + btn + " button " + count + " time(s) at normalized (" + normX + ", " + normY + ") [pixel: " + targetX + ", " + targetY + "].";
                    } catch (Exception e) {
                        return "Error clicking mouse: " + e.getMessage();
                    }
                });
    }

    @Tool("Drag and drop from start normalized coordinates to end normalized coordinates [0..1000].")
    public String dragAndDrop(
            @P("Start normalized horizontal coordinate [0..1000]") Integer startNormX,
            @P("Start vertical coordinate [0..1000]") Integer startNormY,
            @P("End normalized horizontal coordinate [0..1000]") Integer endNormX,
            @P("End vertical coordinate [0..1000]") Integer endNormY) {
        return SwarmTelemetry.executeTool("dragAndDrop",
                SwarmTelemetry.formatArgs("startNormX", startNormX, "startNormY", startNormY, "endNormX", endNormX, "endNormY", endNormY),
                () -> {
                    if (startNormX == null || startNormY == null || endNormX == null || endNormY == null ||
                            startNormX < 0 || startNormX > 1000 || startNormY < 0 || startNormY > 1000 ||
                            endNormX < 0 || endNormX > 1000 || endNormY < 0 || endNormY > 1000) {
                        return "Error: Coordinates must be between 0 and 1000.";
                    }

                    if (!security.requestPermission("drag and drop", "From (" + startNormX + ", " + startNormY + ") to (" + endNormX + ", " + endNormY + ")")) {
                        return "ACTION REJECTED: User explicitly denied drag and drop. Do not retry this action.";
                    }

                    int startPixelX = normalizedToPixelX(startNormX);
                    int startPixelY = normalizedToPixelY(startNormY);
                    int endPixelX = normalizedToPixelX(endNormX);
                    int endPixelY = normalizedToPixelY(endNormY);

                    try {
                        robot.mouseMove(startPixelX, startPixelY);
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        robot.delay(50);
                        try {
                            int steps = 15;
                            for (int step = 1; step <= steps; step++) {
                                double t = (double) step / (double) steps;
                                int currX = (int) Math.round(startPixelX + t * (endPixelX - startPixelX));
                                int currY = (int) Math.round(startPixelY + t * (endPixelY - startPixelY));
                                robot.mouseMove(currX, currY);
                                robot.delay(10);
                            }
                        } finally {
                            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                        }
                        return "Dragged and dropped successfully from (" + startNormX + ", " + startNormY + ") to (" + endNormX + ", " + endNormY + ").";
                    } catch (Exception e) {
                        return "Error performing drag and drop: " + e.getMessage();
                    }
                });
    }

    @Tool("Type text into the focused field with optional clear-before-typing and enter-key submission.")
    public String typeIntoField(
            @P("Text to type into the field") String text,
            @P("Whether to clear existing text in the field first (select all + delete)") Boolean clearFirst,
            @P("Whether to press Enter after typing the text") Boolean pressEnter) {
        return SwarmTelemetry.executeTool("typeIntoField",
                SwarmTelemetry.formatArgs("text", text, "clearFirst", clearFirst, "pressEnter", pressEnter),
                () -> {
                    String safeText = (text == null) ? "" : text;
                    boolean shouldClear = Boolean.TRUE.equals(clearFirst);
                    boolean shouldEnter = Boolean.TRUE.equals(pressEnter);

                    if (!security.requestPermission("type into field", safeText + " (clearFirst=" + shouldClear + ", pressEnter=" + shouldEnter + ")")) {
                        return "ACTION REJECTED: User explicitly denied typing text. Do not retry this action.";
                    }

                    try {
                        boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
                        int modifierKey = isMac ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;

                        if (shouldClear) {
                            robot.keyPress(modifierKey);
                            robot.keyPress(KeyEvent.VK_A);
                            robot.keyRelease(KeyEvent.VK_A);
                            robot.keyRelease(modifierKey);
                            robot.delay(50);
                            robot.keyPress(KeyEvent.VK_BACK_SPACE);
                            robot.keyRelease(KeyEvent.VK_BACK_SPACE);
                            robot.delay(50);
                        }

                        if (!safeText.isEmpty()) {
                            StringSelection stringSelection = new StringSelection(safeText);
                            int attempts = 0;
                            while (true) {
                                try {
                                    clipboard.setContents(stringSelection, null);
                                    break;
                                } catch (IllegalStateException e) {
                                    if (++attempts >= 5) {
                                        throw e;
                                    }
                                    try {
                                        Thread.sleep(50);
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            }

                            robot.keyPress(modifierKey);
                            robot.keyPress(KeyEvent.VK_V);
                            robot.keyRelease(KeyEvent.VK_V);
                            robot.keyRelease(modifierKey);
                            robot.delay(50);
                        }

                        if (shouldEnter) {
                            robot.keyPress(KeyEvent.VK_ENTER);
                            robot.keyRelease(KeyEvent.VK_ENTER);
                        }

                        return "Text typed into field successfully.";
                    } catch (Exception e) {
                        return "Error typing into field: " + e.getMessage();
                    }
                });
    }

    @Tool("Read current text from the system clipboard. Sanitizes any untrusted injection tokens or system headers and caps output at 15,000 characters.")
    public String readClipboard() {
        return SwarmTelemetry.executeTool("readClipboard", "", () -> {
            if (!security.requestPermission("read clipboard", "Read text from system clipboard")) {
                return "ACTION REJECTED: User explicitly denied reading clipboard. Do not retry this action.";
            }
            try {
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    String rawText = (String) clipboard.getData(DataFlavor.stringFlavor);
                    if (rawText == null || rawText.isEmpty()) {
                        return "Clipboard is empty.";
                    }
                    String sanitized = UntrustedContentSanitizer.sanitizeText(rawText);
                    if (sanitized.length() > 15000) {
                        sanitized = sanitized.substring(0, 15000) + "\n...[TRUNCATED: Clipboard content exceeded 15,000 character limit]...";
                    }
                    return sanitized;
                } else {
                    return "Clipboard does not contain readable text content.";
                }
            } catch (Exception e) {
                return "Error reading clipboard: " + e.getMessage();
            }
        });
    }

    @Tool("Copy currently selected text or element to the clipboard via Ctrl+C / Cmd+C.")
    public String copySelection() {
        return SwarmTelemetry.executeTool("copySelection", "", () -> {
            if (!security.requestPermission("copy selection", "Trigger copy hotkey to update clipboard")) {
                return "ACTION REJECTED: User explicitly denied copy selection. Do not retry this action.";
            }
            try {
                boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
                int modifierKey = isMac ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;

                robot.keyPress(modifierKey);
                robot.keyPress(KeyEvent.VK_C);
                robot.keyRelease(KeyEvent.VK_C);
                robot.keyRelease(modifierKey);
                robot.delay(100);

                return "Selection copied to clipboard successfully.";
            } catch (Exception e) {
                return "Error copying selection: " + e.getMessage();
            }
        });
    }
}
