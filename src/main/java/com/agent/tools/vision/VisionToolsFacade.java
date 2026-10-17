package com.agent.tools.vision;

import com.agent.guardrails.UntrustedContentSanitizer;
import com.agent.telemetry.SwarmTelemetry;
import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.vision.hud.CuaHudOverlay;
import com.agent.tools.vision.model.DesktopSkill;
import com.agent.tools.vision.model.GuiActionStep;
import com.agent.tools.vision.model.ScreenDiffResult;
import com.agent.tools.vision.model.WindowInfo;
import com.agent.tools.vision.services.CoordinateConverter;
import com.agent.tools.vision.services.DesktopSkillRegistry;
import com.agent.tools.vision.services.DesktopWindowService;
import com.agent.tools.vision.services.ScreenDiffService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class VisionToolsFacade {
    private final SecurityInterceptor security;
    private final Robot robot;
    private final ChatLanguageModel visionModel;
    private final Dimension screenSize;
    private final java.awt.datatransfer.Clipboard clipboard;
    private final CoordinateConverter coordinateConverter;
    private final DesktopWindowService desktopWindowService;
    private final ScreenDiffService screenDiffService;
    private final DesktopSkillRegistry desktopSkillRegistry;
    private final CuaHudOverlay cuaHudOverlay;
    private final ObjectMapper objectMapper;
    private boolean verifyActionsVisually = true;
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
        this.desktopWindowService = new DesktopWindowService(this.coordinateConverter);
        this.screenDiffService = new ScreenDiffService();
        this.desktopSkillRegistry = new DesktopSkillRegistry();
        this.cuaHudOverlay = new CuaHudOverlay();
        this.objectMapper = new ObjectMapper();
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize) {
        this(security, robot, visionModel, screenSize, Toolkit.getDefaultToolkit().getSystemClipboard());
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize, java.awt.datatransfer.Clipboard clipboard) {
        this(security, robot, visionModel, screenSize, clipboard, null);
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize, java.awt.datatransfer.Clipboard clipboard, DesktopWindowService desktopWindowService) {
        this(security, robot, visionModel, screenSize, clipboard, desktopWindowService, null);
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize, java.awt.datatransfer.Clipboard clipboard, DesktopWindowService desktopWindowService, ScreenDiffService screenDiffService) {
        this(security, robot, visionModel, screenSize, clipboard, desktopWindowService, screenDiffService, null, null);
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize, java.awt.datatransfer.Clipboard clipboard, DesktopWindowService desktopWindowService, ScreenDiffService screenDiffService, DesktopSkillRegistry desktopSkillRegistry, CuaHudOverlay cuaHudOverlay) {
        this.security = security;
        this.robot = robot;
        this.visionModel = visionModel;
        this.screenSize = screenSize != null ? screenSize : new Dimension(1920, 1080);
        this.clipboard = clipboard != null ? clipboard : Toolkit.getDefaultToolkit().getSystemClipboard();
        this.coordinateConverter = new CoordinateConverter(this.screenSize);
        this.desktopWindowService = (desktopWindowService != null) ? desktopWindowService : new DesktopWindowService(this.coordinateConverter);
        this.screenDiffService = (screenDiffService != null) ? screenDiffService : new ScreenDiffService();
        this.desktopSkillRegistry = (desktopSkillRegistry != null) ? desktopSkillRegistry : new DesktopSkillRegistry();
        this.cuaHudOverlay = (cuaHudOverlay != null) ? cuaHudOverlay : new CuaHudOverlay();
        this.objectMapper = new ObjectMapper();
    }

    public DesktopWindowService getDesktopWindowService() {
        return desktopWindowService;
    }

    public ScreenDiffService getScreenDiffService() {
        return screenDiffService;
    }

    public DesktopSkillRegistry getDesktopSkillRegistry() {
        return desktopSkillRegistry;
    }

    public CuaHudOverlay getCuaHudOverlay() {
        return cuaHudOverlay;
    }

    public boolean isVerifyActionsVisually() {
        return verifyActionsVisually;
    }

    public void setVerifyActionsVisually(boolean verifyActionsVisually) {
        this.verifyActionsVisually = verifyActionsVisually;
    }

    private BufferedImage captureScreenSafe() {
        if (robot == null || screenSize == null || screenSize.width <= 0 || screenSize.height <= 0) {
            return null;
        }
        try {
            return robot.createScreenCapture(new Rectangle(screenSize));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String wrapWithVisualVerification(String actionName, String targetCoord, Supplier<String> actionExecution) {
        BufferedImage before = verifyActionsVisually ? captureScreenSafe() : null;
        String result = actionExecution.get();
        if (before != null && result != null && !result.startsWith("Error") && !result.startsWith("ACTION REJECTED")) {
            try {
                if (robot != null) {
                    robot.delay(50);
                }
            } catch (Exception ignored) {}
            BufferedImage after = captureScreenSafe();
            if (after != null) {
                ScreenDiffResult diff = screenDiffService.compare(before, after, coordinateConverter);
                String verificationSummary = diff.toVerificationSummary();
                cuaHudOverlay.recordAction(actionName, targetCoord, verificationSummary);
                return result + " " + verificationSummary;
            }
        }
        cuaHudOverlay.recordAction(actionName, targetCoord, "NO_DIFF");
        return result;
    }

    private String wrapWithVisualVerification(Supplier<String> actionExecution) {
        return wrapWithVisualVerification("ACTION", "N/A", actionExecution);
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

                        // Dynamic Set-of-Marks Window Overlay
                        List<WindowInfo> windows = new ArrayList<>();
                        try {
                            if (desktopWindowService != null) {
                                windows = desktopWindowService.listVisibleWindows();
                            }
                        } catch (Exception ignored) {}

                        for (WindowInfo win : windows) {
                            g2d.setColor(win.active() ? Color.GREEN : Color.ORANGE);
                            g2d.setStroke(new BasicStroke(2.0f));
                            g2d.drawRect(win.x(), win.y(), win.width(), win.height());

                            String rawTitle = (win.title() != null) ? win.title() : "";
                            String truncTitle = rawTitle.length() > 25 ? rawTitle.substring(0, 22) + "..." : rawTitle;
                            String badge = "[W" + win.markId() + (win.active() ? " ACTIVE" : "") + (truncTitle.isEmpty() ? "" : ": " + truncTitle) + "]";

                            int bx = Math.max(0, Math.min(screenSize.width - 150, win.x()));
                            int by = Math.max(16, Math.min(screenSize.height - 10, win.y() + 16));

                            g2d.setFont(new Font("Arial", Font.BOLD, 12));
                            FontMetrics fm = g2d.getFontMetrics();
                            int labelW = fm.stringWidth(badge) + 6;
                            int labelH = fm.getHeight();

                            g2d.setColor(new Color(0, 0, 0, 180));
                            g2d.fillRect(bx, by - labelH + 3, labelW, labelH);

                            g2d.setColor(win.active() ? Color.GREEN : Color.WHITE);
                            g2d.drawString(badge, bx + 3, by);
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

                        StringBuilder windowsTable = new StringBuilder();
                        windowsTable.append("\n\n[[ VISIBLE DESKTOP WINDOWS ]]\n");
                        if (windows.isEmpty()) {
                            windowsTable.append("(No visible windows detected)\n");
                        } else {
                            for (WindowInfo win : windows) {
                                windowsTable.append(String.format("- [W%d] \"%s\" (Process: %s, PID: %d)%s | Bounds: [%d, %d, %dx%d], Norm: [%d, %d, %dx%d], Center: (%d, %d)\n",
                                        win.markId(),
                                        win.title(),
                                        win.processName(),
                                        win.processId(),
                                        win.active() ? " [ACTIVE]" : "",
                                        win.x(), win.y(), win.width(), win.height(),
                                        win.normX(), win.normY(), win.normWidth(), win.normHeight(),
                                        win.centerNormX(), win.centerNormY()));
                            }
                        }

                        String prompt = (question != null ? question : "Describe the contents of the screen.")
                                + " (Note: The image has a 10x10 grid overlay. Reference the red ID numbers 0-99 to specify locations)."
                                + cursorMeta
                                + windowsTable.toString();
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
                    return wrapWithVisualVerification(() -> {
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
                });
    }

    @Tool("Type text into the currently focused window using the clipboard.")
    public String typeText(@P("The text to type") String text) {
        return SwarmTelemetry.executeTool("typeText", SwarmTelemetry.formatArgs("text", text),
                () -> wrapWithVisualVerification(() -> {
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
                }));
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
                    return wrapWithVisualVerification(() -> {
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
                });
    }

    @Tool("Double-click the mouse at the center of a grid ID (0-99).")
    public String doubleClickGridId(@P("Grid cell ID (0-99)") Integer gridId) {
        return SwarmTelemetry.executeTool("doubleClickGridId", SwarmTelemetry.formatArgs("gridId", gridId),
                () -> {
                    if (gridId == null || gridId < 0 || gridId >= (GRID_COLS * GRID_ROWS)) {
                        return "Error: Invalid grid ID. Must be between 0 and 99.";
                    }
                    return wrapWithVisualVerification(() -> {
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
                });
    }

    @Tool("Right-click (context menu) at the center of a grid ID (0-99).")
    public String rightClickGridId(@P("Grid cell ID (0-99)") Integer gridId) {
        return SwarmTelemetry.executeTool("rightClickGridId", SwarmTelemetry.formatArgs("gridId", gridId),
                () -> {
                    if (gridId == null || gridId < 0 || gridId >= (GRID_COLS * GRID_ROWS)) {
                        return "Error: Invalid grid ID. Must be between 0 and 99.";
                    }
                    return wrapWithVisualVerification(() -> {
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
                });
    }

    @Tool("Scroll the mouse wheel. Positive notches scroll down; negative notches scroll up.")
    public String scrollMouse(@P("Number of scroll wheel notches (e.g., 5 for down, -5 for up)") Integer notches) {
        return SwarmTelemetry.executeTool("scrollMouse", SwarmTelemetry.formatArgs("notches", notches),
                () -> wrapWithVisualVerification(() -> {
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
                }));
    }

    private String internalPressHotkey(String hotkey) {
        if (hotkey == null || hotkey.isBlank()) return "Error: Hotkey string cannot be empty.";
        String[] parts = hotkey.trim().toLowerCase().split("\\+");
        List<Integer> keyCodes = new ArrayList<>();

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
    }

    @Tool("Trigger a key or combination hotkey (e.g., 'enter', 'esc', 'tab', 'ctrl+s', 'alt+f4', 'ctrl+shift+p').")
    public String pressHotkey(@P("Hotkey combination separated by '+'") String hotkey) {
        return SwarmTelemetry.executeTool("pressHotkey", SwarmTelemetry.formatArgs("hotkey", hotkey),
                () -> wrapWithVisualVerification(() -> {
                    if (hotkey == null || hotkey.isBlank()) return "Error: Hotkey string cannot be empty.";
                    if (!security.requestPermission("press hotkey", hotkey)) {
                        return "ACTION REJECTED: User explicitly denied hotkey press. Do not retry this action.";
                    }
                    return internalPressHotkey(hotkey);
                }));
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
                    return wrapWithVisualVerification("clickCoordinates", "[" + normX + ", " + normY + "]", () -> {
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
                () -> wrapWithVisualVerification(() -> {
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
                }));
    }

    private String internalTypeText(String text, boolean clearFirst, boolean pressEnter) {
        String safeText = (text == null) ? "" : text;
        try {
            boolean isMac = System.getProperty("os.name").toLowerCase().contains("mac");
            int modifierKey = isMac ? KeyEvent.VK_META : KeyEvent.VK_CONTROL;

            if (clearFirst) {
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

            if (pressEnter) {
                robot.keyPress(KeyEvent.VK_ENTER);
                robot.keyRelease(KeyEvent.VK_ENTER);
            }

            return "Text typed into field successfully.";
        } catch (Exception e) {
            return "Error typing into field: " + e.getMessage();
        }
    }

    @Tool("Type text into the focused field with optional clear-before-typing and enter-key submission.")
    public String typeIntoField(
            @P("Text to type into the field") String text,
            @P("Whether to clear existing text in the field first (select all + delete)") Boolean clearFirst,
            @P("Whether to press Enter after typing the text") Boolean pressEnter) {
        return SwarmTelemetry.executeTool("typeIntoField",
                SwarmTelemetry.formatArgs("text", text, "clearFirst", clearFirst, "pressEnter", pressEnter),
                () -> wrapWithVisualVerification(() -> {
                    String safeText = (text == null) ? "" : text;
                    boolean shouldClear = Boolean.TRUE.equals(clearFirst);
                    boolean shouldEnter = Boolean.TRUE.equals(pressEnter);

                    if (!security.requestPermission("type into field", safeText + " (clearFirst=" + shouldClear + ", pressEnter=" + shouldEnter + ")")) {
                        return "ACTION REJECTED: User explicitly denied typing text. Do not retry this action.";
                    }
                    return internalTypeText(safeText, shouldClear, shouldEnter);
                }));
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

    @Tool("List all open visible desktop windows with their [W#] Set-of-Marks tags, titles, process names, and normalized bounds.")
    public String listOpenWindows() {
        return SwarmTelemetry.executeTool("listOpenWindows", "", () -> {
            List<WindowInfo> windows = desktopWindowService.listVisibleWindows();
            if (windows.isEmpty()) {
                return "No open desktop windows detected.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Open Windows (").append(windows.size()).append("):\n");
            for (WindowInfo win : windows) {
                sb.append(String.format("- [W%d] \"%s\" (Process: %s, PID: %d)%s | Pixel: [%d, %d, %dx%d], Normalized: [%d, %d, %dx%d], Center: (%d, %d)\n",
                        win.markId(),
                        win.title(),
                        win.processName(),
                        win.processId(),
                        win.active() ? " [ACTIVE]" : "",
                        win.x(), win.y(), win.width(), win.height(),
                        win.normX(), win.normY(), win.normWidth(), win.normHeight(),
                        win.centerNormX(), win.centerNormY()));
            }
            return sb.toString().trim();
        });
    }

    @Tool("Get the currently focused foreground desktop window.")
    public String getActiveWindow() {
        return SwarmTelemetry.executeTool("getActiveWindow", "", () -> {
            Optional<WindowInfo> opt = desktopWindowService.getActiveWindow();
            if (opt.isEmpty()) {
                return "No active window detected.";
            }
            WindowInfo win = opt.get();
            return String.format("Active Window: [W%d] \"%s\" (Process: %s, PID: %d) | Normalized Bounds: [%d, %d, %dx%d], Center: (%d, %d).",
                    win.markId(),
                    win.title(),
                    win.processName(),
                    win.processId(),
                    win.normX(), win.normY(), win.normWidth(), win.normHeight(),
                    win.centerNormX(), win.centerNormY());
        });
    }

    @Tool("Bring a desktop window to the foreground by matching its title, process name, or mark tag (e.g., 'Notepad', 'chrome', '[W1]').")
    public String focusWindow(@P("Window title, process name, or mark tag (e.g., 'Notepad', 'chrome', '[W1]')") String windowQuery) {
        return SwarmTelemetry.executeTool("focusWindow", SwarmTelemetry.formatArgs("windowQuery", windowQuery), () -> {
            if (windowQuery == null || windowQuery.isBlank()) {
                return "Error: Window query cannot be empty.";
            }
            if (!security.requestPermission("focus window", windowQuery)) {
                return "ACTION REJECTED: User explicitly denied focusing window. Do not retry this action.";
            }
            return desktopWindowService.focusWindow(windowQuery);
        });
    }

    @Tool("Control the state of a desktop window: maximize, minimize, restore, or close.")
    public String controlWindow(
            @P("Window title, process name, or mark tag (e.g., 'Notepad', '[W1]')") String windowQuery,
            @P("Action to perform: 'maximize', 'minimize', 'restore', or 'close'") String action) {
        return SwarmTelemetry.executeTool("controlWindow",
                SwarmTelemetry.formatArgs("windowQuery", windowQuery, "action", action),
                () -> {
                    if (windowQuery == null || windowQuery.isBlank()) {
                        return "Error: Window query cannot be empty.";
                    }
                    if (action == null || action.isBlank()) {
                        return "Error: Window control action cannot be empty.";
                    }
                    if (!security.requestPermission("control window", "Window: '" + windowQuery + "', Action: '" + action + "'")) {
                        return "ACTION REJECTED: User explicitly denied window control action. Do not retry this action.";
                    }
                    return desktopWindowService.controlWindow(windowQuery, action);
                });
    }

    @Tool("Launch a desktop application or executable by name or path.")
    public String launchApplication(
            @P("Application name or executable path (e.g., 'notepad.exe', 'calc')") String appNameOrPath,
            @P("Optional command line arguments (e.g., 'C:\\file.txt')") String arguments) {
        return SwarmTelemetry.executeTool("launchApplication",
                SwarmTelemetry.formatArgs("appNameOrPath", appNameOrPath, "arguments", arguments),
                () -> {
                    if (appNameOrPath == null || appNameOrPath.isBlank()) {
                        return "Error: Application name or path cannot be empty.";
                    }
                    if (!security.requestPermission("launch application", "App: '" + appNameOrPath + "', Args: '" + (arguments != null ? arguments : "") + "'")) {
                        return "ACTION REJECTED: User explicitly denied launching application. Do not retry this action.";
                    }
                    return desktopWindowService.launchApplication(appNameOrPath, arguments);
                });
    }

    @Tool("Click inside a desktop window at a relative offset (0.0 to 1.0). Center is 0.5, 0.5; top-left is 0.0, 0.0; top-right is 0.98, 0.02.")
    public String clickWindow(
            @P("Window title, process name, or mark tag (e.g., 'Notepad', '[W1]')") String windowQuery,
            @P("Horizontal relative offset inside window (0.0 to 1.0, default 0.5)") Double offsetPercentX,
            @P("Vertical relative offset inside window (0.0 to 1.0, default 0.5)") Double offsetPercentY) {
        return SwarmTelemetry.executeTool("clickWindow",
                SwarmTelemetry.formatArgs("windowQuery", windowQuery, "offsetPercentX", offsetPercentX, "offsetPercentY", offsetPercentY),
                () -> wrapWithVisualVerification(() -> {
                    if (windowQuery == null || windowQuery.isBlank()) {
                        return "Error: Window query cannot be empty.";
                    }
                    Optional<WindowInfo> opt = desktopWindowService.findWindow(windowQuery);
                    if (opt.isEmpty()) {
                        return "No visible window found matching query: '" + windowQuery + "'. Use listOpenWindows to see available windows.";
                    }
                    WindowInfo win = opt.get();
                    double ox = (offsetPercentX != null) ? offsetPercentX : 0.5;
                    double oy = (offsetPercentY != null) ? offsetPercentY : 0.5;
                    int targetNormX = win.relativePointNormX(ox);
                    int targetNormY = win.relativePointNormY(oy);
                    int targetPixelX = normalizedToPixelX(targetNormX);
                    int targetPixelY = normalizedToPixelY(targetNormY);

                    if (!security.requestPermission("click window", "Window [W" + win.markId() + "] '" + win.title() + "' at (" + ox + ", " + oy + ") -> Norm (" + targetNormX + ", " + targetNormY + ")")) {
                        return "ACTION REJECTED: User explicitly denied clicking window. Do not retry this action.";
                    }

                    try {
                        robot.mouseMove(targetPixelX, targetPixelY);
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                        return "Clicked window [W" + win.markId() + "] ('" + win.title() + "') at relative (" + ox + ", " + oy + ") -> normalized (" + targetNormX + ", " + targetNormY + ") [pixel: " + targetPixelX + ", " + targetPixelY + "].";
                    } catch (Exception e) {
                        return "Error clicking window: " + e.getMessage();
                    }
                }));
    }

    @Tool("Poll the screen and block until visual changes cease (settles) or maxWaitMs (default: 3000ms, max: 10000ms) is reached.")
    public String waitForScreenSettle(
            @P("Maximum milliseconds to wait for screen to stabilize (default 3000, max 10000)") Integer maxWaitMs) {
        return SwarmTelemetry.executeTool("waitForScreenSettle",
                SwarmTelemetry.formatArgs("maxWaitMs", maxWaitMs),
                () -> {
                    int timeout = (maxWaitMs != null && maxWaitMs > 0) ? Math.min(maxWaitMs, 10000) : 3000;
                    ScreenDiffResult diff = screenDiffService.waitForScreenSettle(
                            this::captureScreenSafe,
                            coordinateConverter,
                            timeout,
                            100
                    );
                    if (diff.totalPixels() == 0) {
                        return "Screen settle check completed (display capture unavailable/headless).";
                    }
                    if (diff.visuallyChanged()) {
                        return "Screen settled after visual activity. " + diff.toVerificationSummary();
                    } else {
                        return "Screen remained visually stable (no significant changes detected). " + diff.toVerificationSummary();
                    }
                });
    }

    @Tool("Execute a compound sequence of GUI actions atomically in a single turn. Input is a JSON array of step objects, e.g. [{\"action\":\"focus_window\",\"windowQuery\":\"Notepad\"},{\"action\":\"click_coordinates\",\"normX\":500,\"normY\":500},{\"action\":\"type\",\"text\":\"hello\",\"pressEnter\":true}].")
    public String executeGuiMacro(
            @P("JSON array of GUI action step objects") String stepsJson) {
        return SwarmTelemetry.executeTool("executeGuiMacro",
                SwarmTelemetry.formatArgs("stepsJson", stepsJson),
                () -> {
                    if (stepsJson == null || stepsJson.isBlank()) {
                        return "Error: Macro steps JSON cannot be empty.";
                    }

                    List<GuiActionStep> steps;
                    try {
                        steps = objectMapper.readValue(stepsJson, new TypeReference<List<GuiActionStep>>() {});
                    } catch (Exception e) {
                        return "Error parsing macro steps JSON: " + e.getMessage();
                    }

                    if (steps == null || steps.isEmpty()) {
                        return "Error: Macro must contain at least one step.";
                    }
                    if (steps.size() > 50) {
                        return "Error: Macro exceeds maximum limit of 50 steps.";
                    }

                    // Upfront validation of all steps before execution
                    for (int i = 0; i < steps.size(); i++) {
                        GuiActionStep step = steps.get(i);
                        if (step == null || step.action() == null || step.action().isBlank()) {
                            return "Error: Step " + (i + 1) + " must specify an action.";
                        }
                        String act = step.action().trim().toLowerCase().replace("-", "_");
                        switch (act) {
                            case "focus_window", "focuswindow", "focus" -> {
                                if (step.windowQuery() == null || step.windowQuery().isBlank()) {
                                    return "Error: Step " + (i + 1) + " (focus_window) requires 'windowQuery'.";
                                }
                            }
                            case "control_window", "controlwindow", "control" -> {
                                if (step.windowQuery() == null || step.windowQuery().isBlank()) {
                                    return "Error: Step " + (i + 1) + " (control_window) requires 'windowQuery'.";
                                }
                                if (step.windowAction() == null || step.windowAction().isBlank()) {
                                    return "Error: Step " + (i + 1) + " (control_window) requires 'windowAction'.";
                                }
                            }
                            case "click_coordinates", "clickcoordinates", "click" -> {
                                if (step.normX() == null || step.normY() == null ||
                                        step.normX() < 0 || step.normX() > 1000 ||
                                        step.normY() < 0 || step.normY() > 1000) {
                                    return "Error: Step " + (i + 1) + " (click_coordinates) requires valid normX and normY in [0..1000].";
                                }
                            }
                            case "click_window", "clickwindow" -> {
                                if (step.windowQuery() == null || step.windowQuery().isBlank()) {
                                    return "Error: Step " + (i + 1) + " (click_window) requires 'windowQuery'.";
                                }
                            }
                            case "type", "type_text", "typetext", "type_into_field", "typeintofield" -> {
                                if (step.text() == null) {
                                    return "Error: Step " + (i + 1) + " (type) requires 'text'.";
                                }
                            }
                            case "hotkey", "press_hotkey", "presshotkey" -> {
                                if (step.hotkey() == null || step.hotkey().isBlank()) {
                                    return "Error: Step " + (i + 1) + " (hotkey) requires 'hotkey'.";
                                }
                            }
                            case "drag_and_drop", "draganddrop", "drag" -> {
                                if (step.normX() == null || step.normY() == null ||
                                        step.endNormX() == null || step.endNormY() == null ||
                                        step.normX() < 0 || step.normX() > 1000 ||
                                        step.normY() < 0 || step.normY() > 1000 ||
                                        step.endNormX() < 0 || step.endNormX() > 1000 ||
                                        step.endNormY() < 0 || step.endNormY() > 1000) {
                                    return "Error: Step " + (i + 1) + " (drag_and_drop) requires valid normX, normY, endNormX, and endNormY in [0..1000].";
                                }
                            }
                            case "scroll", "scroll_mouse", "scrollmouse", "wait", "sleep", "settle", "wait_for_settle", "waitforscreensettle" -> {
                                // optional parameters
                            }
                            default -> {
                                return "Error: Step " + (i + 1) + " has unrecognized action '" + step.action() + "'.";
                            }
                        }
                    }

                    // Single consolidated security check for the entire macro
                    String actionSummary = steps.stream()
                            .map(s -> s.action() != null ? s.action() : "step")
                            .collect(Collectors.joining(" -> "));
                    if (!security.requestPermission("execute GUI macro", steps.size() + " steps: " + actionSummary)) {
                        return "ACTION REJECTED: User explicitly denied GUI macro execution. Do not retry this action.";
                    }

                    BufferedImage before = verifyActionsVisually ? captureScreenSafe() : null;
                    List<String> stepSummaries = executeStepsInternal(steps);

                    StringBuilder sb = new StringBuilder();
                    sb.append("Macro executed successfully (").append(steps.size()).append(" steps executed):\n");
                    for (int i = 0; i < stepSummaries.size(); i++) {
                        sb.append("  ").append(i + 1).append(". ").append(stepSummaries.get(i)).append("\n");
                    }
                    String verificationSummary = "[Unverified]";
                    if (before != null) {
                        try {
                            if (robot != null) robot.delay(50);
                        } catch (Exception ignored) {}
                        BufferedImage after = captureScreenSafe();
                        if (after != null) {
                            ScreenDiffResult diff = screenDiffService.compare(before, after, coordinateConverter);
                            verificationSummary = diff.toVerificationSummary();
                            sb.append("\n").append(verificationSummary);
                        }
                    }
                    cuaHudOverlay.recordAction("execute_gui_macro", steps.size() + " steps", verificationSummary);
                    return sb.toString().trim();
                });
    }

    private List<String> executeStepsInternal(List<GuiActionStep> steps) {
        List<String> stepSummaries = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            GuiActionStep step = steps.get(i);
            String act = step.action().trim().toLowerCase().replace("-", "_");
            try {
                switch (act) {
                    case "focus_window", "focuswindow", "focus" -> {
                        String focusRes = desktopWindowService.focusWindow(step.windowQuery());
                        stepSummaries.add("Focused window: '" + step.windowQuery() + "' (" + focusRes + ")");
                    }
                    case "control_window", "controlwindow", "control" -> {
                        String ctrlRes = desktopWindowService.controlWindow(step.windowQuery(), step.windowAction());
                        stepSummaries.add("Controlled window '" + step.windowQuery() + "' (" + step.windowAction() + "): " + ctrlRes);
                    }
                    case "click_coordinates", "clickcoordinates", "click" -> {
                        int tx = normalizedToPixelX(step.normX());
                        int ty = normalizedToPixelY(step.normY());
                        String btn = (step.button() != null && !step.button().isBlank()) ? step.button().trim().toLowerCase() : "left";
                        int count = (step.clickCount() != null && step.clickCount() > 0) ? Math.min(step.clickCount(), 3) : 1;
                        int mask = switch (btn) {
                            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
                            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
                            default -> InputEvent.BUTTON1_DOWN_MASK;
                        };
                        robot.mouseMove(tx, ty);
                        for (int c = 0; c < count; c++) {
                            robot.mousePress(mask);
                            robot.mouseRelease(mask);
                            if (c < count - 1) robot.delay(100);
                        }
                        stepSummaries.add("Clicked " + btn + " button " + count + " time(s) at normalized (" + step.normX() + ", " + step.normY() + ")");
                    }
                    case "click_window", "clickwindow" -> {
                        Optional<WindowInfo> opt = desktopWindowService.findWindow(step.windowQuery());
                        if (opt.isEmpty()) {
                            stepSummaries.add("Window not found for click: '" + step.windowQuery() + "'");
                        } else {
                            WindowInfo win = opt.get();
                            double ox = (step.offsetPercentX() != null) ? step.offsetPercentX() : 0.5;
                            double oy = (step.offsetPercentY() != null) ? step.offsetPercentY() : 0.5;
                            int tx = normalizedToPixelX(win.relativePointNormX(ox));
                            int ty = normalizedToPixelY(win.relativePointNormY(oy));
                            robot.mouseMove(tx, ty);
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                            stepSummaries.add("Clicked window [W" + win.markId() + "] '" + win.title() + "' at relative (" + ox + ", " + oy + ")");
                        }
                    }
                    case "type", "type_text", "typetext", "type_into_field", "typeintofield" -> {
                        boolean clear = Boolean.TRUE.equals(step.clearFirst());
                        boolean enter = Boolean.TRUE.equals(step.pressEnter());
                        internalTypeText(step.text(), clear, enter);
                        stepSummaries.add("Typed text (clearFirst=" + clear + ", pressEnter=" + enter + "): \"" + step.text() + "\"");
                    }
                    case "hotkey", "press_hotkey", "presshotkey" -> {
                        internalPressHotkey(step.hotkey());
                        stepSummaries.add("Executed hotkey: " + step.hotkey());
                    }
                    case "drag_and_drop", "draganddrop", "drag" -> {
                        int sx = normalizedToPixelX(step.normX());
                        int sy = normalizedToPixelY(step.normY());
                        int ex = normalizedToPixelX(step.endNormX());
                        int ey = normalizedToPixelY(step.endNormY());
                        robot.mouseMove(sx, sy);
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        robot.delay(50);
                        int stepsCount = 15;
                        for (int st = 1; st <= stepsCount; st++) {
                            double t = (double) st / (double) stepsCount;
                            int currX = (int) Math.round(sx + t * (ex - sx));
                            int currY = (int) Math.round(sy + t * (ey - sy));
                            robot.mouseMove(currX, currY);
                            robot.delay(10);
                        }
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                        stepSummaries.add("Dragged and dropped from (" + step.normX() + ", " + step.normY() + ") to (" + step.endNormX() + ", " + step.endNormY() + ")");
                    }
                    case "scroll", "scroll_mouse", "scrollmouse" -> {
                        int notches = (step.notches() != null) ? step.notches() : 5;
                        robot.mouseWheel(notches);
                        stepSummaries.add("Scrolled mouse " + notches + " notches");
                    }
                    case "wait", "sleep" -> {
                        int waitTime = (step.waitMs() != null && step.waitMs() > 0) ? Math.min(step.waitMs(), 10000) : 100;
                        try {
                            Thread.sleep(waitTime);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        stepSummaries.add("Waited " + waitTime + "ms");
                    }
                    case "settle", "wait_for_settle", "waitforscreensettle" -> {
                        int settleWait = (step.waitMs() != null && step.waitMs() > 0) ? Math.min(step.waitMs(), 10000) : 3000;
                        screenDiffService.waitForScreenSettle(this::captureScreenSafe, coordinateConverter, settleWait, 100);
                        stepSummaries.add("Waited for screen settle (" + settleWait + "ms max)");
                    }
                }
            } catch (Exception ex) {
                stepSummaries.add("Step " + (i + 1) + " (" + step.action() + ") error: " + ex.getMessage());
            }
        }
        return stepSummaries;
    }

    @Tool("List all available built-in and custom Desktop CUA Skills with their parameters and action counts.")
    public String listDesktopSkills() {
        return SwarmTelemetry.executeTool("listDesktopSkills", "{}", () -> {
            return desktopSkillRegistry.formatCatalog();
        });
    }

    @Tool("Execute a parameterized Desktop CUA Skill macro by skillId with dynamic JSON parameters.")
    public String executeDesktopSkill(
            @P(value = "The ID of the desktop skill to execute (e.g. 'browser_navigate_url', 'notepad_write_save', 'system_quick_find')", required = true) String skillId,
            @P(value = "JSON object map containing dynamic parameters matching the skill's required parameterNames, e.g. '{\"url\": \"https://github.com\"}'", required = false) String parametersJson
    ) {
        return SwarmTelemetry.executeTool("executeDesktopSkill",
                SwarmTelemetry.formatArgs("skillId", skillId, "parametersJson", parametersJson),
                () -> {
            if (skillId == null || skillId.isBlank()) {
                return "Error: skillId cannot be empty.";
            }

            Optional<DesktopSkill> skillOpt = desktopSkillRegistry.getSkill(skillId.trim());
            if (skillOpt.isEmpty()) {
                return "Error: Skill '" + skillId + "' not found. Call listDesktopSkills to see available skills.";
            }
            DesktopSkill skill = skillOpt.get();

            Map<String, String> params = new HashMap<>();
            if (parametersJson != null && !parametersJson.isBlank()) {
                try {
                    Map<String, Object> rawParams = objectMapper.readValue(parametersJson, new TypeReference<Map<String, Object>>() {});
                    if (rawParams != null) {
                        for (Map.Entry<String, Object> entry : rawParams.entrySet()) {
                            params.put(entry.getKey(), entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                        }
                    }
                } catch (Exception e) {
                    return "Error: Failed to parse parametersJson: " + e.getMessage();
                }
            }

            List<GuiActionStep> resolvedSteps;
            try {
                resolvedSteps = skill.resolveSteps(params);
            } catch (IllegalArgumentException e) {
                return "Error: " + e.getMessage();
            }

            if (!security.requestPermission("execute desktop skill", skill.skillId() + " (" + resolvedSteps.size() + " steps)")) {
                return "ACTION REJECTED: User explicitly denied desktop skill execution. Do not retry this action.";
            }

            BufferedImage before = verifyActionsVisually ? captureScreenSafe() : null;
            List<String> stepSummaries = executeStepsInternal(resolvedSteps);

            StringBuilder sb = new StringBuilder();
            sb.append("Desktop skill '").append(skill.skillId()).append("' executed successfully (").append(resolvedSteps.size()).append(" steps executed):\n");
            for (int i = 0; i < stepSummaries.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(stepSummaries.get(i)).append("\n");
            }
            String verificationSummary = "[Unverified]";
            if (before != null) {
                try {
                    if (robot != null) robot.delay(50);
                } catch (Exception ignored) {}
                BufferedImage after = captureScreenSafe();
                if (after != null) {
                    ScreenDiffResult diff = screenDiffService.compare(before, after, coordinateConverter);
                    verificationSummary = diff.toVerificationSummary();
                    sb.append("\n").append(verificationSummary);
                }
            }
            cuaHudOverlay.recordAction("execute_skill:" + skill.skillId(), resolvedSteps.size() + " steps", verificationSummary);
            return sb.toString().trim();
        });
    }

    @Tool("Register and persist a new custom Desktop CUA Skill manifest JSON.")
    public String saveDesktopSkill(
            @P(value = "Full JSON definition of the DesktopSkill (skillId, name, description, osPlatform, parameterNames, steps)", required = true) String skillJson
    ) {
        return SwarmTelemetry.executeTool("saveDesktopSkill",
                SwarmTelemetry.formatArgs("skillJson", skillJson),
                () -> {
            if (skillJson == null || skillJson.isBlank()) {
                return "Error: skillJson cannot be empty.";
            }

            DesktopSkill skill;
            try {
                skill = objectMapper.readValue(skillJson, DesktopSkill.class);
            } catch (Exception e) {
                return "Error: Failed to parse skillJson: " + e.getMessage();
            }

            if (skill.skillId() == null || skill.skillId().isBlank()) {
                return "Error: skillId is required.";
            }
            if (skill.steps() == null || skill.steps().isEmpty()) {
                return "Error: skill must contain at least one action step.";
            }

            if (!security.requestPermission("save desktop skill", skill.skillId() + " (" + skill.name() + ")")) {
                return "ACTION REJECTED: User explicitly denied saving desktop skill. Do not retry this action.";
            }

            try {
                desktopSkillRegistry.registerSkill(skill, true);
                cuaHudOverlay.recordAction("save_skill:" + skill.skillId(), skill.steps().size() + " steps", "SAVED");
                return "Desktop skill '" + skill.skillId() + "' registered and saved successfully.";
            } catch (Exception e) {
                return "Error saving skill: " + e.getMessage();
            }
        });
    }

    @Tool("Configure or toggle the non-blocking visual HUD overlay indicator on desktop.")
    public String configureCuaHud(
            @P(value = "Enable (true) or disable (false) the visual HUD overlay. If null, toggles the current state.", required = false) Boolean enabled
    ) {
        return SwarmTelemetry.executeTool("configureCuaHud",
                SwarmTelemetry.formatArgs("enabled", String.valueOf(enabled)),
                () -> {
            boolean newState = (enabled != null) ? enabled : !cuaHudOverlay.isEnabled();
            cuaHudOverlay.setEnabled(newState);
            return "CUA HUD Overlay configured: " + (newState ? "ENABLED" : "DISABLED") + ". " + cuaHudOverlay.getStatusSnapshot();
        });
    }
}
