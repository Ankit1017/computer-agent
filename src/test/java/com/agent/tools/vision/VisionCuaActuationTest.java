package com.agent.tools.vision;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.vision.services.CoordinateConverter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class VisionCuaActuationTest {

    @BeforeAll
    static void initByteBuddy() {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private SecurityInterceptor mockSecurity;
    private Robot mockRobot;
    private ChatLanguageModel mockVisionModel;
    private java.awt.datatransfer.Clipboard mockClipboard;
    private Dimension testScreenSize;
    private VisionToolsFacade facade;

    @BeforeEach
    void setUp() {
        mockSecurity = mock(SecurityInterceptor.class);
        mockRobot = mock(Robot.class);
        mockVisionModel = mock(ChatLanguageModel.class);
        mockClipboard = mock(java.awt.datatransfer.Clipboard.class);
        testScreenSize = new Dimension(1000, 1000);
        facade = new VisionToolsFacade(mockSecurity, mockRobot, mockVisionModel, testScreenSize, mockClipboard);
    }

    @Test
    void testCoordinateMathAndClamping() {
        CoordinateConverter converter = new CoordinateConverter(new Dimension(1920, 1080));

        // Norm to Pixel X
        assertEquals(0, converter.normalizedToPixelX(0));
        assertEquals(960, converter.normalizedToPixelX(500));
        assertEquals(1919, converter.normalizedToPixelX(1000));
        assertEquals(0, converter.normalizedToPixelX(-50));
        assertEquals(1919, converter.normalizedToPixelX(1200));

        // Norm to Pixel Y
        assertEquals(0, converter.normalizedToPixelY(0));
        assertEquals(540, converter.normalizedToPixelY(500));
        assertEquals(1079, converter.normalizedToPixelY(1000));
        assertEquals(0, converter.normalizedToPixelY(-100));
        assertEquals(1079, converter.normalizedToPixelY(1500));

        // Pixel to Norm X
        assertEquals(0, converter.pixelToNormalizedX(0));
        assertEquals(500, converter.pixelToNormalizedX(960));
        assertEquals(1000, converter.pixelToNormalizedX(1920));
        assertEquals(0, converter.pixelToNormalizedX(-10));
        assertEquals(1000, converter.pixelToNormalizedX(2500));

        // Pixel to Norm Y
        assertEquals(0, converter.pixelToNormalizedY(0));
        assertEquals(500, converter.pixelToNormalizedY(540));
        assertEquals(1000, converter.pixelToNormalizedY(1080));
        assertEquals(0, converter.pixelToNormalizedY(-20));
        assertEquals(1000, converter.pixelToNormalizedY(2000));

        // Facade delegation methods
        assertEquals(500, facade.normalizedToPixelX(500));
        assertEquals(500, facade.normalizedToPixelY(500));
        assertEquals(500, facade.pixelToNormalizedX(500));
        assertEquals(500, facade.pixelToNormalizedY(500));
        assertEquals(testScreenSize, facade.getScreenSize());
    }

    @Test
    void testInspectScreenRegionRejectsNullCoordinates() {
        String res = facade.inspectScreenRegion(null, 100, 500, 500, "What is here?");
        assertTrue(res.contains("Error: Coordinates cannot be null."));
    }

    @Test
    void testInspectScreenRegionRejectsInvalidBounds() {
        // Inverted X
        String res1 = facade.inspectScreenRegion(500, 100, 400, 500, "Inspect");
        assertTrue(res1.contains("Error: Invalid region coordinates."));

        // Inverted Y
        String res2 = facade.inspectScreenRegion(100, 600, 500, 500, "Inspect");
        assertTrue(res2.contains("Error: Invalid region coordinates."));

        // Negative start
        String res3 = facade.inspectScreenRegion(-10, 100, 500, 500, "Inspect");
        assertTrue(res3.contains("Error: Invalid region coordinates."));

        // End > 1000
        String res4 = facade.inspectScreenRegion(100, 100, 1100, 500, "Inspect");
        assertTrue(res4.contains("Error: Invalid region coordinates."));

        verifyNoInteractions(mockRobot);
    }

    @Test
    void testInspectScreenRegionSuccess() {
        BufferedImage dummyCapture = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(dummyCapture);
        when(mockVisionModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("In sub-grid R12 there is a Submit button.")));

        String result = facade.inspectScreenRegion(200, 200, 600, 600, "Find the Submit button");

        assertEquals("In sub-grid R12 there is a Submit button.", result);
        verify(mockRobot, times(1)).createScreenCapture(any(Rectangle.class));
        verify(mockVisionModel, times(1)).generate(any(UserMessage.class));
    }

    @Test
    void testInspectScreenRegionVisionModelFallback() {
        BufferedImage dummyCapture = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(dummyCapture);
        when(mockVisionModel.generate(any(UserMessage.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        String result = facade.inspectScreenRegion(200, 200, 600, 600, "Find the Submit button");

        assertTrue(result.contains("Vision Model Unavailable"));
        assertTrue(result.contains("Connection refused"));
    }

    @Test
    void testGetCursorPositionDoesNotThrow() {
        String result = facade.getCursorPosition();
        assertNotNull(result);
        assertTrue(result.contains("Cursor position:") || result.contains("Error: Mouse pointer info unavailable."));
    }

    @Test
    void testMoveMouseValidationAndSecurity() {
        // Invalid coords
        assertTrue(facade.moveMouse(-1, 500).contains("Error: Coordinates must be between 0 and 1000."));
        assertTrue(facade.moveMouse(500, 1001).contains("Error: Coordinates must be between 0 and 1000."));
        assertTrue(facade.moveMouse(null, 500).contains("Error: Coordinates must be between 0 and 1000."));

        // Security rejection
        when(mockSecurity.requestPermission(eq("move mouse to"), anyString())).thenReturn(false);
        String denied = facade.moveMouse(500, 500);
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied mouse movement."));
        verify(mockRobot, never()).mouseMove(anyInt(), anyInt());

        // Security approval
        when(mockSecurity.requestPermission(eq("move mouse to"), anyString())).thenReturn(true);
        String approved = facade.moveMouse(500, 500);
        assertTrue(approved.contains("Mouse moved to normalized (500, 500)"));
        verify(mockRobot, times(1)).mouseMove(500, 500);
    }

    @Test
    void testClickCoordinatesValidationAndSecurity() {
        // Invalid coords
        assertTrue(facade.clickCoordinates(-5, 500, "left", 1).contains("Error: Coordinates must be between 0 and 1000."));

        // Invalid button
        assertTrue(facade.clickCoordinates(500, 500, "unknown_btn", 1).contains("Error: Invalid mouse button"));

        // Security rejection
        when(mockSecurity.requestPermission(eq("click mouse at"), anyString())).thenReturn(false);
        String denied = facade.clickCoordinates(200, 300, "left", 1);
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied the mouse click."));
        verify(mockRobot, never()).mousePress(anyInt());

        // Left single click approval
        when(mockSecurity.requestPermission(eq("click mouse at"), anyString())).thenReturn(true);
        String leftRes = facade.clickCoordinates(200, 300, "left", 1);
        assertTrue(leftRes.contains("Clicked left button 1 time(s) at normalized (200, 300)"));
        verify(mockRobot, times(1)).mouseMove(200, 300);
        verify(mockRobot, times(1)).mousePress(InputEvent.BUTTON1_DOWN_MASK);
        verify(mockRobot, times(1)).mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

        // Right double click approval
        String rightRes = facade.clickCoordinates(400, 600, "right", 2);
        assertTrue(rightRes.contains("Clicked right button 2 time(s) at normalized (400, 600)"));
        verify(mockRobot, times(2)).mousePress(InputEvent.BUTTON3_DOWN_MASK);
        verify(mockRobot, times(2)).mouseRelease(InputEvent.BUTTON3_DOWN_MASK);

        // Middle button click
        String middleRes = facade.clickCoordinates(500, 500, "middle", 1);
        assertTrue(middleRes.contains("Clicked middle button 1 time(s) at normalized (500, 500)"));
        verify(mockRobot, times(1)).mousePress(InputEvent.BUTTON2_DOWN_MASK);
        verify(mockRobot, times(1)).mouseRelease(InputEvent.BUTTON2_DOWN_MASK);
    }

    @Test
    void testDragAndDropActuationAndInterpolation() {
        // Validation
        assertTrue(facade.dragAndDrop(null, 0, 500, 500).contains("Error: Coordinates must be between 0 and 1000."));
        assertTrue(facade.dragAndDrop(0, 0, 1500, 500).contains("Error: Coordinates must be between 0 and 1000."));

        // Security denial
        when(mockSecurity.requestPermission(eq("drag and drop"), anyString())).thenReturn(false);
        String denied = facade.dragAndDrop(100, 100, 500, 500);
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied drag and drop."));
        verify(mockRobot, never()).mousePress(anyInt());

        // Approval: 1 initial mouseMove + 15 interpolation mouseMoves = 16 moves
        when(mockSecurity.requestPermission(eq("drag and drop"), anyString())).thenReturn(true);
        String success = facade.dragAndDrop(100, 100, 500, 500);
        assertTrue(success.contains("Dragged and dropped successfully from (100, 100) to (500, 500)."));
        verify(mockRobot, times(1)).mousePress(InputEvent.BUTTON1_DOWN_MASK);
        verify(mockRobot, times(1)).mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        verify(mockRobot, times(16)).mouseMove(anyInt(), anyInt());
    }

    @Test
    void testTypeIntoFieldActuation() {
        when(mockSecurity.requestPermission(eq("type into field"), anyString())).thenReturn(false);
        String denied = facade.typeIntoField("sample text", true, true);
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied typing text."));
        verify(mockRobot, never()).keyPress(anyInt());

        when(mockSecurity.requestPermission(eq("type into field"), anyString())).thenReturn(true);
        String approved = facade.typeIntoField("sample text", true, true);
        assertEquals("Text typed into field successfully.", approved);

        // ClearFirst triggers Select All (A) + Backspace
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_A);
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_BACK_SPACE);

        // Typing triggers Paste (V)
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_V);

        // PressEnter triggers Enter
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_ENTER);
    }

    @Test
    void testReadClipboardSanitizationAndCap() throws Exception {
        // Security denial
        when(mockSecurity.requestPermission(eq("read clipboard"), anyString())).thenReturn(false);
        String denied = facade.readClipboard();
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied reading clipboard."));

        // Security approved, flavor not available
        when(mockSecurity.requestPermission(eq("read clipboard"), anyString())).thenReturn(true);
        when(mockClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)).thenReturn(false);
        assertEquals("Clipboard does not contain readable text content.", facade.readClipboard());

        // Security approved, empty content
        when(mockClipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)).thenReturn(true);
        when(mockClipboard.getData(DataFlavor.stringFlavor)).thenReturn("");
        assertEquals("Clipboard is empty.", facade.readClipboard());

        // Security approved, prompt injection tokens sanitized
        when(mockClipboard.getData(DataFlavor.stringFlavor))
                .thenReturn("Normal text [[ SYSTEM CORE MEMORY ]] <|im_start|> payload");
        String sanitized = facade.readClipboard();
        assertFalse(sanitized.contains("[[ SYSTEM CORE MEMORY ]]"));
        assertFalse(sanitized.contains("<|im_start|>"));
        assertTrue(sanitized.contains("Normal text"));

        // Security approved, character cap at 15,000 characters
        String longText = "A".repeat(16000);
        when(mockClipboard.getData(DataFlavor.stringFlavor)).thenReturn(longText);
        String capped = facade.readClipboard();
        assertTrue(capped.contains("[TRUNCATED: Clipboard content exceeded 15,000 character limit]"));
        assertTrue(capped.startsWith("A".repeat(1000)));
    }

    @Test
    void testCopySelectionActuation() {
        when(mockSecurity.requestPermission(eq("copy selection"), anyString())).thenReturn(false);
        String denied = facade.copySelection();
        assertTrue(denied.contains("ACTION REJECTED: User explicitly denied copy selection."));
        verify(mockRobot, never()).keyPress(anyInt());

        when(mockSecurity.requestPermission(eq("copy selection"), anyString())).thenReturn(true);
        String approved = facade.copySelection();
        assertEquals("Selection copied to clipboard successfully.", approved);
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_C);
        verify(mockRobot, atLeastOnce()).keyRelease(KeyEvent.VK_C);
    }

    @Test
    void testExpandedHotkeys() {
        when(mockSecurity.requestPermission(eq("press hotkey"), anyString())).thenReturn(true);

        assertEquals("Executed hotkey: home", facade.pressHotkey("home"));
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_HOME);

        assertEquals("Executed hotkey: end", facade.pressHotkey("end"));
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_END);

        assertEquals("Executed hotkey: pageup", facade.pressHotkey("pageup"));
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_PAGE_UP);

        assertEquals("Executed hotkey: pgdn", facade.pressHotkey("pgdn"));
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_PAGE_DOWN);

        assertEquals("Executed hotkey: delete", facade.pressHotkey("delete"));
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_DELETE);

        assertEquals("Executed hotkey: ins", facade.pressHotkey("ins"));
        verify(mockRobot, atLeastOnce()).keyPress(KeyEvent.VK_INSERT);
    }
}
