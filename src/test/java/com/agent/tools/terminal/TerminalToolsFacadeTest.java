package com.agent.tools.terminal;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.terminal.services.CommandExecutorService;
import com.agent.tools.terminal.services.FileSystemService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class TerminalToolsFacadeTest {

    @org.junit.jupiter.api.BeforeAll
    static void initByteBuddy() {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private SecurityInterceptor mockSecurity;
    private CommandExecutorService mockCmdService;
    private FileSystemService mockFsService;
    private TerminalToolsFacade facade;

    @BeforeEach
    void setUp() {
        mockSecurity = mock(SecurityInterceptor.class);
        mockCmdService = mock(CommandExecutorService.class);
        mockFsService = mock(FileSystemService.class);
        facade = new TerminalToolsFacade(mockSecurity, mockCmdService, mockFsService);
    }

    @Test
    void shouldBlockWriteFileWhenSecurityDenied() {
        // Arrange
        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(false);

        // Act
        String result = facade.writeFile("test.txt", "malicious payload");

        // Assert
        assertEquals("User denied file write.", result);
        verify(mockFsService, never()).writeFile(anyString(), anyString());
    }

    @Test
    void shouldBlockExecuteCommandWhenSecurityDenied() {
        // Arrange
        when(mockSecurity.requestPermission(anyString(), anyString())).thenReturn(false);

        // Act
        String result = facade.executeCommand("rm -rf /");

        // Assert
        assertEquals("User denied command execution.", result);
        verify(mockCmdService, never()).execute(anyString());
    }

    @Test
    void shouldAllowReadOperationsWithoutSecurityPrompt() {
        // Arrange
        when(mockFsService.readFileLines("test.txt", 1, 10)).thenReturn("File content");

        // Act
        String result = facade.readFileLines("test.txt", 1, 10);

        // Assert
        assertEquals("File content", result);
        verify(mockSecurity, never()).requestPermission(anyString(), anyString());
        verify(mockFsService, times(1)).readFileLines("test.txt", 1, 10);
    }

    @Test
    void shouldDelegateListDirectoryOverloadsProperly() {
        when(mockFsService.listDirectory("src", null, 4)).thenReturn("Tree 1");
        when(mockFsService.listDirectory("src", ".*test.*", 4)).thenReturn("Tree 2");
        when(mockFsService.listDirectory("src", ".*test.*", 2)).thenReturn("Tree 3");

        assertEquals("Tree 1", facade.listDirectory("src"));
        assertEquals("Tree 2", facade.listDirectory("src", ".*test.*"));
        assertEquals("Tree 3", facade.listDirectory("src", ".*test.*", 2));

        verify(mockFsService, times(1)).listDirectory("src", null, 4);
        verify(mockFsService, times(1)).listDirectory("src", ".*test.*", 4);
        verify(mockFsService, times(1)).listDirectory("src", ".*test.*", 2);
    }

    @Test
    void shouldDelegateSearchDirectoryOverloadsProperly() {
        when(mockFsService.searchDirectory("src", "Service", null)).thenReturn("Matches 1");
        when(mockFsService.searchDirectory("src", "Service", ".java")).thenReturn("Matches 2");

        assertEquals("Matches 1", facade.searchDirectory("src", "Service"));
        assertEquals("Matches 2", facade.searchDirectory("src", "Service", ".java"));

        verify(mockFsService, times(1)).searchDirectory("src", "Service", null);
        verify(mockFsService, times(1)).searchDirectory("src", "Service", ".java");
    }
}
