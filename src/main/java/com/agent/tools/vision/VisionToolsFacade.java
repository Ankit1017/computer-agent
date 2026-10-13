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

        // Instantiate a dedicated model just for vision tasks
        this.visionModel = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("llama3.2-vision") // Ensure this model is pulled in Ollama
                .temperature(0.0)
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    public VisionToolsFacade(SecurityInterceptor security, Robot robot, ChatLanguageModel visionModel, Dimension screenSize) {
        this.security = security;
        this.robot = robot;
        this.visionModel = visionModel;
        this.screenSize = screenSize != null ? screenSize : new Dimension(1920, 1080);
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
            String prompt = question + " (Note: The image has a 10x10 grid overlay. Reference the red ID numbers 0-99 to specify locations).";
            UserMessage msg = UserMessage.from(
                TextContent.from(prompt),
                ImageContent.from(base64Image, "image/png")
            );

            return visionModel.generate(msg).content().text();
        } catch (Exception e) {
            return "Error capturing or analyzing screen: " + e.getMessage();
        }
    }

    @Tool("Move the mouse to the center of a specific grid ID (0-99) and click.")
    public String clickGridId(@P("The numeric ID of the grid cell to click (0-99)") int gridId) {
        if (gridId < 0 || gridId >= (GRID_COLS * GRID_ROWS)) {
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
            return "User denied mouse click.";
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
        if (!security.requestPermission("type text", text)) {
            return "User denied typing text.";
        }
        try {
            StringSelection stringSelection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);

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
}
