package com.agent.tools.vision;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.vision.model.WindowInfo;
import com.agent.tools.vision.services.CoordinateConverter;
import com.agent.tools.vision.services.DesktopWindowService;
import com.agent.tools.vision.services.WindowSystemProvider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class DesktopWindowServiceTest {

    @BeforeAll
    static void initByteBuddy() {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private WindowSystemProvider mockProvider;
    private CoordinateConverter coordinateConverter;
    private DesktopWindowService windowService;
    private SecurityInterceptor mockSecurity;
    private Robot mockRobot;
    private ChatLanguageModel mockVisionModel;
    private java.awt.datatransfer.Clipboard mockClipboard;
    private VisionToolsFacade facade;

    @BeforeEach
    void setUp() {
        mockProvider = mock(WindowSystemProvider.class);
        coordinateConverter = new CoordinateConverter(new Dimension(1920, 1080));
        windowService = new DesktopWindowService(coordinateConverter, mockProvider);

        mockSecurity = mock(SecurityInterceptor.class);
        mockRobot = mock(Robot.class);
        mockVisionModel = mock(ChatLanguageModel.class);
        mockClipboard = mock(java.awt.datatransfer.Clipboard.class);

        facade = new VisionToolsFacade(
                mockSecurity,
                mockRobot,
                mockVisionModel,
                new Dimension(1920, 1080),
                mockClipboard,
                windowService
        );
    }

    @Test
    void testWindowEnumerationAndNormalizedBoundsMapping() {
        // Mock 2 windows: Left half and Right half of 1920x1080 screen
        WindowSystemProvider.RawWindow win1 = new WindowSystemProvider.RawWindow(
                101L, 1234L, "Code.exe", "Visual Studio Code - Project",
                0, 0, 960, 1080, true
        );
        WindowSystemProvider.RawWindow win2 = new WindowSystemProvider.RawWindow(
                102L, 5678L, "notepad.exe", "Untitled - Notepad",
                960, 0, 960, 1080, false
        );

        when(mockProvider.enumerateWindows()).thenReturn(List.of(win1, win2));

        List<WindowInfo> windows = windowService.listVisibleWindows();
        assertEquals(2, windows.size());

        // Window 1 assertions
        WindowInfo w1 = windows.get(0);
        assertEquals(1, w1.markId());
        assertEquals("[W1]", w1.tag());
        assertEquals(1234L, w1.processId());
        assertEquals("Code.exe", w1.processName());
        assertEquals("Visual Studio Code - Project", w1.title());
        assertEquals(0, w1.x());
        assertEquals(0, w1.y());
        assertEquals(960, w1.width());
        assertEquals(1080, w1.height());
        assertTrue(w1.active());

        // Normalized bounds: x=0, y=0, w=500, h=1000
        assertEquals(0, w1.normX());
        assertEquals(0, w1.normY());
        assertEquals(500, w1.normWidth());
        assertEquals(1000, w1.normHeight());

        // Centers
        assertEquals(250, w1.centerNormX());
        assertEquals(500, w1.centerNormY());

        // Relative point offsets
        assertEquals(0, w1.relativePointNormX(0.0));
        assertEquals(250, w1.relativePointNormX(0.5));
        assertEquals(500, w1.relativePointNormX(1.0));
        assertEquals(0, w1.relativePointNormY(0.0));
        assertEquals(500, w1.relativePointNormY(0.5));
        assertEquals(1000, w1.relativePointNormY(1.0));

        // Window 2 assertions
        WindowInfo w2 = windows.get(1);
        assertEquals(2, w2.markId());
        assertEquals("[W2]", w2.tag());
        assertFalse(w2.active());
        assertEquals(500, w2.normX());
        assertEquals(0, w2.normY());
        assertEquals(500, w2.normWidth());
        assertEquals(1000, w2.normHeight());
        assertEquals(750, w2.centerNormX());
        assertEquals(500, w2.centerNormY());
    }

    @Test
    void testActiveWindowAndSubstringLookup() {
        WindowSystemProvider.RawWindow win1 = new WindowSystemProvider.RawWindow(
                101L, 1234L, "Code.exe", "Visual Studio Code - Project",
                0, 0, 960, 1080, true
        );
        WindowSystemProvider.RawWindow win2 = new WindowSystemProvider.RawWindow(
                102L, 5678L, "notepad.exe", "Untitled - Notepad",
                960, 0, 960, 1080, false
        );

        when(mockProvider.enumerateWindows()).thenReturn(List.of(win1, win2));
        when(mockProvider.focusWindow(any())).thenReturn(true);

        // Active window
        Optional<WindowInfo> activeOpt = windowService.getActiveWindow();
        assertTrue(activeOpt.isPresent());
        assertEquals("Visual Studio Code - Project", activeOpt.get().title());

        // Substring lookup by title
        String focusResult = windowService.focusWindow("Visual Studio");
        assertTrue(focusResult.contains("Focused window [W1]: 'Visual Studio Code - Project'"));
        verify(mockProvider, times(1)).focusWindow(any());

        // Substring lookup by process
        String focusNotepad = windowService.focusWindow("notepad");
        assertTrue(focusNotepad.contains("Focused window [W2]: 'Untitled - Notepad'"));

        // Lookup by tag
        String focusTag = windowService.focusWindow("[W2]");
        assertTrue(focusTag.contains("Focused window [W2]: 'Untitled - Notepad'"));

        // Missing window query
        String missingResult = windowService.focusWindow("Calculator");
        assertTrue(missingResult.contains("No visible window found matching query: 'Calculator'"));

        // Empty query
        String emptyResult = windowService.focusWindow("");
        assertTrue(emptyResult.contains("Error: Window query cannot be empty."));
    }

    @Test
    void testWindowStateControlAndSecurityGate() {
        WindowSystemProvider.RawWindow win = new WindowSystemProvider.RawWindow(
                102L, 5678L, "notepad.exe", "Untitled - Notepad",
                960, 0, 960, 1080, false
        );
        when(mockProvider.enumerateWindows()).thenReturn(List.of(win));
        when(mockProvider.controlWindow(any(), anyString())).thenReturn(true);

        // Valid actions on service directly
        String maxRes = windowService.controlWindow("Notepad", "maximize");
        assertTrue(maxRes.contains("action 'maximize' executed successfully."));

        String minRes = windowService.controlWindow("Notepad", "minimize");
        assertTrue(minRes.contains("action 'minimize' executed successfully."));

        String restoreRes = windowService.controlWindow("Notepad", "restore");
        assertTrue(restoreRes.contains("action 'restore' executed successfully."));

        String closeRes = windowService.controlWindow("Notepad", "close");
        assertTrue(closeRes.contains("action 'close' executed successfully."));

        // Invalid action
        String invalidAct = windowService.controlWindow("Notepad", "destroy");
        assertTrue(invalidAct.contains("Error: Invalid window control action 'destroy'"));

        // Security gate on Facade tool: Denied
        when(mockSecurity.requestPermission(eq("control window"), anyString())).thenReturn(false);
        String denied = facade.controlWindow("Notepad", "close");
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied window control action."));
        verify(mockProvider, times(4)).controlWindow(any(), anyString()); // not called again

        // Security gate on Facade tool: Approved
        when(mockSecurity.requestPermission(eq("control window"), anyString())).thenReturn(true);
        String approved = facade.controlWindow("Notepad", "close");
        assertTrue(approved.contains("action 'close' executed successfully."));
    }

    @Test
    void testClickWindowRelativeTargeting() {
        // Setup a 1000x1000 screen facade so pixel bounds match normalized bounds 1:1
        Dimension squareScreen = new Dimension(1000, 1000);
        CoordinateConverter squareConverter = new CoordinateConverter(squareScreen);
        DesktopWindowService squareWindowService = new DesktopWindowService(squareConverter, mockProvider);
        VisionToolsFacade squareFacade = new VisionToolsFacade(
                mockSecurity,
                mockRobot,
                mockVisionModel,
                squareScreen,
                mockClipboard,
                squareWindowService
        );

        // Window at [200, 100, width=600, height=800]
        WindowSystemProvider.RawWindow win = new WindowSystemProvider.RawWindow(
                102L, 5678L, "notepad.exe", "Notepad",
                200, 100, 600, 800, false
        );
        when(mockProvider.enumerateWindows()).thenReturn(List.of(win));

        // Security denial
        when(mockSecurity.requestPermission(eq("click window"), anyString())).thenReturn(false);
        String denied = squareFacade.clickWindow("Notepad", 0.5, 0.5);
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied clicking window."));
        verify(mockRobot, never()).mouseMove(anyInt(), anyInt());

        // Security approval: Click center (0.5, 0.5)
        // Center of [200..800] is 500, Center of [100..900] is 500
        when(mockSecurity.requestPermission(eq("click window"), anyString())).thenReturn(true);
        String centerRes = squareFacade.clickWindow("Notepad", 0.5, 0.5);
        assertTrue(centerRes.contains("Clicked window [W1] ('Notepad') at relative (0.5, 0.5) -> normalized (500, 500) [pixel: 500, 500]."));
        verify(mockRobot, times(1)).mouseMove(500, 500);
        verify(mockRobot, times(1)).mousePress(InputEvent.BUTTON1_DOWN_MASK);
        verify(mockRobot, times(1)).mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        // Click top-right corner offset (0.98, 0.02)
        // X = 200 + round(0.98 * 600) = 200 + 588 = 788
        // Y = 100 + round(0.02 * 800) = 100 + 16 = 116
        String cornerRes = squareFacade.clickWindow("Notepad", 0.98, 0.02);
        assertTrue(cornerRes.contains("normalized (788, 116) [pixel: 788, 116]."));
        verify(mockRobot, times(1)).mouseMove(788, 116);
    }

    @Test
    void testDynamicSoMWindowOverlayRendering() {
        // Windows with negative / off-screen coordinates
        WindowSystemProvider.RawWindow win1 = new WindowSystemProvider.RawWindow(
                1L, 100L, "App1.exe", "Offscreen Left",
                -200, -100, 800, 600, true
        );
        WindowSystemProvider.RawWindow win2 = new WindowSystemProvider.RawWindow(
                2L, 200L, "App2.exe", "Offscreen Right",
                1800, 900, 600, 400, false
        );
        when(mockProvider.enumerateWindows()).thenReturn(List.of(win1, win2));

        BufferedImage dummyCapture = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(dummyCapture);
        when(mockVisionModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("Detected windows on screen.")));

        // Must render cleanly without throwing exceptions
        String inspectResult = facade.inspectScreen("List windows");
        assertEquals("Detected windows on screen.", inspectResult);

        // Verify prompt captured the [[ VISIBLE DESKTOP WINDOWS ]] grounding table
        ArgumentCaptor<UserMessage> captor = ArgumentCaptor.forClass(UserMessage.class);
        verify(mockVisionModel).generate(captor.capture());
        UserMessage message = captor.getValue();
        String promptText = message.contents().stream()
                .filter(c -> c instanceof dev.langchain4j.data.message.TextContent)
                .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                .findFirst().orElse("");

        assertTrue(promptText.contains("[[ VISIBLE DESKTOP WINDOWS ]]"));
        assertTrue(promptText.contains("[W1] \"Offscreen Left\" (Process: App1.exe, PID: 100) [ACTIVE]"));
        assertTrue(promptText.contains("[W2] \"Offscreen Right\" (Process: App2.exe, PID: 200)"));
    }

    @Test
    void testListOpenWindowsAndGetActiveWindowTools() {
        WindowSystemProvider.RawWindow win1 = new WindowSystemProvider.RawWindow(
                101L, 1234L, "Code.exe", "VS Code",
                0, 0, 960, 1080, true
        );
        when(mockProvider.enumerateWindows()).thenReturn(List.of(win1));

        String listRes = facade.listOpenWindows();
        assertTrue(listRes.contains("Open Windows (1):"));
        assertTrue(listRes.contains("[W1] \"VS Code\" (Process: Code.exe, PID: 1234) [ACTIVE]"));

        String activeRes = facade.getActiveWindow();
        assertTrue(activeRes.contains("Active Window: [W1] \"VS Code\" (Process: Code.exe, PID: 1234)"));
    }

    @Test
    void testLaunchApplicationTool() {
        // Security denial
        when(mockSecurity.requestPermission(eq("launch application"), anyString())).thenReturn(false);
        String denied = facade.launchApplication("calc.exe", null);
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied launching application."));

        // Dangerous characters rejection
        when(mockSecurity.requestPermission(eq("launch application"), anyString())).thenReturn(true);
        String illegal = facade.launchApplication("calc.exe; rm -rf /", null);
        assertTrue(illegal.contains("Error: Application command contains illegal shell control characters."));

        // Empty app name
        String empty = facade.launchApplication("", null);
        assertTrue(empty.contains("Error: Application name or path cannot be empty."));
    }
}
