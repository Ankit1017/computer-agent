package com.agent.tools.vision;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.vision.model.GuiActionStep;
import com.agent.tools.vision.model.ScreenDiffResult;
import com.agent.tools.vision.model.WindowInfo;
import com.agent.tools.vision.services.CoordinateConverter;
import com.agent.tools.vision.services.DesktopWindowService;
import com.agent.tools.vision.services.ScreenDiffService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ScreenDiffAndMacroTest {

    private SecurityInterceptor mockSecurity;
    private Robot mockRobot;
    private ChatLanguageModel mockVisionModel;
    private Clipboard mockClipboard;
    private DesktopWindowService mockDesktopWindowService;
    private ScreenDiffService screenDiffService;
    private CoordinateConverter converter;
    private Dimension testScreenSize;
    private VisionToolsFacade facade;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockSecurity = mock(SecurityInterceptor.class);
        mockRobot = mock(Robot.class);
        mockVisionModel = mock(ChatLanguageModel.class);
        mockClipboard = mock(Clipboard.class);
        testScreenSize = new Dimension(1920, 1080);
        converter = new CoordinateConverter(testScreenSize);
        mockDesktopWindowService = mock(DesktopWindowService.class);
        screenDiffService = new ScreenDiffService();
        objectMapper = new ObjectMapper();

        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(true);

        facade = new VisionToolsFacade(
                mockSecurity,
                mockRobot,
                mockVisionModel,
                testScreenSize,
                mockClipboard,
                mockDesktopWindowService,
                screenDiffService
        );
    }

    @Test
    void testIdenticalFramesComparison() {
        BufferedImage img1 = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage img2 = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);

        ScreenDiffResult diff = screenDiffService.compare(img1, img2, converter);

        assertEquals(0.0, diff.changedPixelPercent(), 0.001);
        assertEquals(0, diff.changedPixelCount());
        assertEquals(1920L * 1080L, diff.totalPixels());
        assertFalse(diff.visuallyChanged());

        String summary = diff.toVerificationSummary();
        assertTrue(summary.contains("[VISUAL WARNING:"));
        assertTrue(summary.contains("0.00%"));
    }

    @Test
    void testLocalizedChangeComparisonAndBoundingBox() {
        BufferedImage img1 = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage img2 = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);

        // Fill region from (960, 540) to (1440, 810) with bright red (480 x 270 = 129,600 pixels)
        Graphics2D g = img2.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(960, 540, 480, 270);
        g.dispose();

        ScreenDiffResult diff = screenDiffService.compare(img1, img2, converter);

        assertTrue(diff.visuallyChanged());
        assertEquals(129600, diff.changedPixelCount());
        assertEquals(6.25, diff.changedPixelPercent(), 0.01);

        // Normalized bounding box:
        // 960 / 1920 * 1000 = 500
        // 540 / 1080 * 1000 = 500
        // (960 + 480 - 1) = 1439 -> 1439 / 1920 * 1000 = 749 or 750
        // (540 + 270 - 1) = 809 -> 809 / 1080 * 1000 = 749 or 750
        assertEquals(500, diff.minNormX());
        assertEquals(500, diff.minNormY());
        assertTrue(diff.maxNormX() >= 749 && diff.maxNormX() <= 750, "maxNormX should be ~750 but was: " + diff.maxNormX());
        assertTrue(diff.maxNormY() >= 749 && diff.maxNormY() <= 750, "maxNormY should be ~750 but was: " + diff.maxNormY());

        String summary = diff.toVerificationSummary();
        assertTrue(summary.contains("[VISUAL VERIFICATION:"));
        assertTrue(summary.contains("6.25%"));
        assertTrue(summary.contains("[500, 500 ->"));
    }

    @Test
    void testRgbNoiseTolerance() {
        BufferedImage img1 = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        BufferedImage img2 = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);

        // Change color by 10 in R, G, and B (within default tolerance of 15)
        int lowNoiseRgb = new Color(10, 10, 10).getRGB();
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                img2.setRGB(x, y, lowNoiseRgb);
            }
        }

        ScreenDiffResult diff = screenDiffService.compare(img1, img2, converter);
        assertEquals(0, diff.changedPixelCount());
        assertEquals(0.0, diff.changedPixelPercent(), 0.001);
        assertFalse(diff.visuallyChanged());

        // Now test with higher difference (e.g. RGB 25, 25, 25 > 15 tolerance)
        int highDiffRgb = new Color(25, 25, 25).getRGB();
        img2.setRGB(50, 50, highDiffRgb);
        ScreenDiffResult diffHigh = screenDiffService.compare(img1, img2, converter);
        assertEquals(1, diffHigh.changedPixelCount());
    }

    @Test
    void testWaitForScreenSettleConvergence() {
        BufferedImage f0 = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        BufferedImage f1 = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < 5; p++) f1.setRGB(p, 0, Color.WHITE.getRGB());

        BufferedImage f2 = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < 10; p++) f2.setRGB(p, 0, Color.RED.getRGB());

        BufferedImage f3 = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < 10; p++) f3.setRGB(p, 0, Color.RED.getRGB()); // identical to f2

        BufferedImage[] frames = new BufferedImage[]{f0, f1, f2, f3};
        AtomicInteger index = new AtomicInteger(0);

        ScreenDiffResult settled = screenDiffService.waitForScreenSettle(
                () -> {
                    int i = index.getAndIncrement();
                    return (i < frames.length) ? frames[i] : frames[frames.length - 1];
                },
                converter,
                1000,
                50
        );

        assertNotNull(settled);
        assertTrue(settled.visuallyChanged());
        assertEquals(10, settled.changedPixelCount());

        // Test with null frame supplier
        ScreenDiffResult nullDiff = screenDiffService.waitForScreenSettle(null, converter, 1000, 50);
        assertEquals(0, nullDiff.totalPixels());
    }

    @Test
    void testPostActionVerificationSuccess() {
        BufferedImage before = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage after = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = after.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(100, 100, 500, 500); // Changed region
        g.dispose();

        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(before, after);

        String result = facade.clickCoordinates(500, 500, "left", 1);

        assertTrue(result.contains("Clicked left button 1 time(s) at normalized (500, 500)"));
        assertTrue(result.contains("[VISUAL VERIFICATION: Screen changed by"));
    }

    @Test
    void testPostActionVerificationWarningOnNoChange() {
        BufferedImage before = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage after = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB); // Identical

        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(before, after);

        String result = facade.clickGridId(45);

        assertTrue(result.contains("Clicked successfully at Grid ID 45."));
        assertTrue(result.contains("[VISUAL WARNING: 0.00% screen change detected after action — UI did not visibly react"));
    }

    @Test
    void testPostActionVerificationGracefulWhenHeadless() {
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(null);

        String result = facade.pressHotkey("enter");

        assertEquals("Executed hotkey: enter", result);
        assertFalse(result.contains("[VISUAL"));
    }

    @Test
    void testExecuteGuiMacroSuccess() throws Exception {
        WindowInfo dummyWin = new WindowInfo(
                1,
                1234L,
                "notepad.exe",
                "Notepad - Untitled",
                100, 100, 800, 600,
                52, 93, 417, 556,
                true
        );
        when(mockDesktopWindowService.findWindow("Notepad")).thenReturn(Optional.of(dummyWin));
        when(mockDesktopWindowService.focusWindow("Notepad")).thenReturn("Window brought to foreground: 'Notepad - Untitled'");

        BufferedImage before = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        BufferedImage after = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        after.setRGB(500, 500, Color.WHITE.getRGB());
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(before, after);

        List<GuiActionStep> steps = List.of(
                GuiActionStep.focusWindow("Notepad"),
                GuiActionStep.clickCoordinates(500, 500, "left", 1),
                GuiActionStep.type("Hello World", true, true),
                GuiActionStep.hotkey("ctrl+s")
        );
        String stepsJson = objectMapper.writeValueAsString(steps);

        String macroResult = facade.executeGuiMacro(stepsJson);

        // Security should only be prompted ONCE for the whole macro
        verify(mockSecurity, times(1)).requestPermission(eq("execute GUI macro"), anyString());

        assertTrue(macroResult.contains("Macro executed successfully (4 steps executed):"));
        assertTrue(macroResult.contains("1. Focused window: 'Notepad'"));
        assertTrue(macroResult.contains("2. Clicked left button 1 time(s) at normalized (500, 500)"));
        assertTrue(macroResult.contains("3. Typed text (clearFirst=true, pressEnter=true): \"Hello World\""));
        assertTrue(macroResult.contains("4. Executed hotkey: ctrl+s"));
        assertTrue(macroResult.contains("[VISUAL VERIFICATION:") || macroResult.contains("[VISUAL WARNING:"));
    }

    @Test
    void testExecuteGuiMacroSecurityRejection() throws Exception {
        when(mockSecurity.requestPermission(eq("execute GUI macro"), anyString())).thenReturn(false);

        List<GuiActionStep> steps = List.of(
                GuiActionStep.focusWindow("Notepad"),
                GuiActionStep.hotkey("ctrl+c")
        );
        String stepsJson = objectMapper.writeValueAsString(steps);

        String result = facade.executeGuiMacro(stepsJson);

        assertEquals("ACTION REJECTED: User explicitly denied GUI macro execution. Do not retry this action.", result);
        verify(mockRobot, never()).mouseMove(anyInt(), anyInt());
        verify(mockRobot, never()).keyPress(anyInt());
    }

    @Test
    void testExecuteGuiMacroValidationErrors() {
        // Null and blank JSON
        assertTrue(facade.executeGuiMacro(null).contains("Error: Macro steps JSON cannot be empty."));
        assertTrue(facade.executeGuiMacro("   ").contains("Error: Macro steps JSON cannot be empty."));

        // Malformed JSON
        assertTrue(facade.executeGuiMacro("{ invalid json }").contains("Error parsing macro steps JSON:"));

        // Empty steps array
        assertTrue(facade.executeGuiMacro("[]").contains("Error: Macro must contain at least one step."));

        // Step missing action
        assertTrue(facade.executeGuiMacro("[{}]").contains("Error: Step 1 must specify an action."));

        // Unrecognized action
        assertTrue(facade.executeGuiMacro("[{\"action\":\"fly_to_moon\"}]").contains("has unrecognized action 'fly_to_moon'."));

        // focus_window without windowQuery
        assertTrue(facade.executeGuiMacro("[{\"action\":\"focus_window\"}]").contains("(focus_window) requires 'windowQuery'."));

        // control_window without windowAction
        assertTrue(facade.executeGuiMacro("[{\"action\":\"control_window\",\"windowQuery\":\"Notepad\"}]").contains("(control_window) requires 'windowAction'."));

        // click_coordinates out of bounds
        assertTrue(facade.executeGuiMacro("[{\"action\":\"click_coordinates\",\"normX\":-10,\"normY\":500}]").contains("(click_coordinates) requires valid normX and normY in [0..1000]."));
        assertTrue(facade.executeGuiMacro("[{\"action\":\"click_coordinates\",\"normX\":500,\"normY\":1050}]").contains("(click_coordinates) requires valid normX and normY in [0..1000]."));

        // type without text
        assertTrue(facade.executeGuiMacro("[{\"action\":\"type\"}]").contains("(type) requires 'text'."));

        // hotkey without hotkey
        assertTrue(facade.executeGuiMacro("[{\"action\":\"hotkey\"}]").contains("(hotkey) requires 'hotkey'."));

        // drag_and_drop without end coordinates
        assertTrue(facade.executeGuiMacro("[{\"action\":\"drag_and_drop\",\"normX\":100,\"normY\":100}]").contains("(drag_and_drop) requires valid normX, normY, endNormX, and endNormY in [0..1000]."));
    }

    @Test
    void testWaitForScreenSettleTool() {
        // Headless check
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(null);
        String headlessResult = facade.waitForScreenSettle(2000);
        assertTrue(headlessResult.contains("Screen settle check completed (display capture unavailable/headless)."));

        // Non-headless stable check
        BufferedImage frame = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(frame);
        String stableResult = facade.waitForScreenSettle(500);
        assertTrue(stableResult.contains("Screen remained visually stable") && stableResult.contains("[VISUAL WARNING:"));
    }
}
