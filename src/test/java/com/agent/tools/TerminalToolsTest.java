package com.agent.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalToolsTest {

    @Test
    @DisplayName("Should list files in an existing directory")
    void shouldListDirectory(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("sample1.txt"));
        Files.createFile(tempDir.resolve("sample2.txt"));

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String listing = tools.listDirectory(tempDir.toString());
        assertThat(listing).contains("sample1.txt");
        assertThat(listing).contains("sample2.txt");
    }

    @Test
    @DisplayName("Should read file content and truncate if exceeding 3000 characters")
    void shouldReadFileAndTruncate(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("large.txt");
        String largeContent = "A".repeat(4000);
        Files.writeString(file, largeContent);

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String readResult = tools.readFile(file.toString());
        assertThat(readResult).hasSizeLessThanOrEqualTo(3050);
        assertThat(readResult).contains("[truncated]");
    }

    @Test
    @DisplayName("Should execute terminal command when user approves with 'Y'")
    void shouldExecuteCommandWhenApproved() {
        Scanner scanner = new Scanner(new ByteArrayInputStream("Y\n".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String result = tools.executeCommand("echo Antigravity");
        assertThat(result.trim()).contains("Antigravity");
    }

    @Test
    @DisplayName("Should deny execution and not run command when user enters 'N'")
    void shouldDenyCommandWhenRejected() {
        Scanner scanner = new Scanner(new ByteArrayInputStream("N\n".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String result = tools.executeCommand("echo Antigravity");
        assertThat(result).isEqualTo("User denied execution.");
    }
}
