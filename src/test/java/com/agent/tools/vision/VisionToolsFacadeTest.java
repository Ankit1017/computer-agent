package com.agent.tools.vision;

import com.agent.tools.terminal.security.SecurityInterceptor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class VisionToolsFacadeTest {

    @BeforeAll
    static void initByteBuddy() {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private SecurityInterceptor mockSecurity;
    private Robot mockRobot;
    private ChatLanguageModel mockVisionModel;
    private Dimension testScreenSize;
    private java.awt.datatransfer.Clipboard mockClipboard;
    private VisionToolsFacade facade;

    @BeforeEach
    void setUp() {
        mockSecurity = mock(SecurityInterceptor.class);
        mockRobot = mock(Robot.class);
        mockVisionModel = mock(ChatLanguageModel.class);
        mockClipboard = mock(java.awt.datatransfer.Clipboard.class);
        testScreenSize = new Dimension(1000, 1000); // 100x100 cells
        facade = new VisionToolsFacade(mockSecurity, mockRobot, mockVisionModel, testScreenSize, mockClipboard);
    }

    @Test
    void shouldRejectInvalidGridId() {
        String resultUnder = facade.clickGridId(-1);
        String resultOver = facade.clickGridId(100);

        assertTrue(resultUnder.contains("Error: Invalid grid ID"));
        assertTrue(resultOver.contains("Error: Invalid grid ID"));
        verifyNoInteractions(mockRobot);
    }

    @Test
    void shouldBlockClickGridIdWhenSecurityDenied() {
        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(false);

        String result = facade.clickGridId(45);

        assertEquals("ACTION REJECTED: User explicitly denied the mouse click. Do not retry this action.", result);
        verify(mockSecurity, times(1)).requestPermission(
                eq("click mouse at"),
                eq("Grid ID:45 (X:550, Y:450)")
        );
        verify(mockRobot, never()).mouseMove(anyInt(), anyInt());
        verify(mockRobot, never()).mousePress(anyInt());
    }

    @Test
    void shouldPerformClickWhenSecurityApproved() {
        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(true);

        String result = facade.clickGridId(45);

        assertEquals("Clicked successfully at Grid ID 45.", result);
        verify(mockRobot, times(1)).mouseMove(550, 450);
        verify(mockRobot, times(1)).mousePress(InputEvent.BUTTON1_DOWN_MASK);
        verify(mockRobot, times(1)).mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    @Test
    void shouldBlockTypeTextWhenSecurityDenied() {
        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(false);

        String result = facade.typeText("hello world");

        assertEquals("ACTION REJECTED: User explicitly denied typing text. Do not retry this action.", result);
        verify(mockRobot, never()).keyPress(anyInt());
    }

    @Test
    void shouldPerformTypeTextWhenSecurityApproved() {
        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(true);

        String result = facade.typeText("hello world");

        assertEquals("Text typed successfully.", result);
        verify(mockRobot, atLeastOnce()).keyPress(anyInt());
        verify(mockRobot, atLeastOnce()).keyRelease(anyInt());
    }

    @Test
    void shouldInspectScreenAndDelegateToVisionModel() {
        BufferedImage dummyCapture = new BufferedImage(1000, 1000, BufferedImage.TYPE_INT_RGB);
        when(mockRobot.createScreenCapture(any(Rectangle.class))).thenReturn(dummyCapture);
        when(mockVisionModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("At Grid ID 45 there is a text editor window.")));

        String result = facade.inspectScreen("What is around Grid ID 45?");

        assertEquals("At Grid ID 45 there is a text editor window.", result);
        verify(mockRobot, times(1)).createScreenCapture(any(Rectangle.class));
        verify(mockVisionModel, times(1)).generate(any(UserMessage.class));
    }
}
