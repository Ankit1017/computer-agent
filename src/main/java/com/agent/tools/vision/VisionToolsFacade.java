package com.agent.tools.vision;

import com.agent.tools.terminal.security.SecurityInterceptor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import javax.imageio.ImageIO;
import java.awt.*;
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
    }

    @Tool("Capture the screen. The image is pre-processed with a Set-of-Marks grid (IDs 0 to 99). Ask the model what is located at a specific ID, or ask it to find the ID of a target application.")
    public String inspectScreen(@P("The question to ask the vision model about the screen.") String question) {
        try {
            // 1. Capture screen
            Rectangle screenRect = new Rectangle(screenSize);
            BufferedImage capture = robot.createScreenCapture(screenRect);

            // 2. Apply Set-of-Marks Grid Overlay
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
            g2d.dispose();

            // 3. Convert to Base64 (PNG)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(capture, "png", baos);
            String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());

            // 4. Construct LangChain4j multimodal message
            String prompt = (question != null ? question : "Describe the contents of the screen.")
                    + " (Note: The image has a 10x10 grid overlay. Reference the red ID numbers 0-99 to specify locations).";
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
    }

    @Tool("Move the mouse to the center of a specific grid ID (0-99) and click.")
    public String clickGridId(@P("The numeric ID of the grid cell to click (0-99)") Integer gridId) {
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
    }

    @Tool("Type text into the currently focused window using the clipboard.")
    public String typeText(@P("The text to type") String text) {
        if (text == null) {
            text = "";
        }
        if (!security.requestPermission("type text", text)) {
            return "ACTION REJECTED: User explicitly denied typing text. Do not retry this action.";
        }
        try {
            StringSelection stringSelection = new StringSelection(text);
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
    }

    @Tool("Click at a precise relative offset inside a grid cell (0-99). offsetPercentX and offsetPercentY range from 0.0 (top-left) to 1.0 (bottom-right). Center is 0.5, 0.5.")
    public String clickGridOffset(
            @P("Grid cell ID (0-99)") Integer gridId,
            @P("Horizontal offset within cell (0.0 to 1.0)") Double offsetPercentX,
            @P("Vertical offset within cell (0.0 to 1.0)") Double offsetPercentY) {
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
    }

    @Tool("Double-click the mouse at the center of a grid ID (0-99).")
    public String doubleClickGridId(@P("Grid cell ID (0-99)") Integer gridId) {
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
    }

    @Tool("Right-click (context menu) at the center of a grid ID (0-99).")
    public String rightClickGridId(@P("Grid cell ID (0-99)") Integer gridId) {
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
    }

    @Tool("Scroll the mouse wheel. Positive notches scroll down; negative notches scroll up.")
    public String scrollMouse(@P("Number of scroll wheel notches (e.g., 5 for down, -5 for up)") Integer notches) {
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
    }

    @Tool("Trigger a key or combination hotkey (e.g., 'enter', 'esc', 'tab', 'ctrl+s', 'alt+f4', 'ctrl+shift+p').")
    public String pressHotkey(@P("Hotkey combination separated by '+'") String hotkey) {
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
}
