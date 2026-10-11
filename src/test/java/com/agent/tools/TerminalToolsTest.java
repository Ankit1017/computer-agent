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

    @Test
    @DisplayName("Should write file when user approves with 'Y'")
    void shouldWriteFileWhenApproved(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("output.py");
        Scanner scanner = new Scanner(new ByteArrayInputStream("Y\n".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String result = tools.writeFile(target.toString(), "print('Hello World')");
        assertThat(result).contains("File written successfully");
        assertThat(Files.readString(target)).isEqualTo("print('Hello World')");
    }

    @Test
    @DisplayName("Should deny writing file when user enters 'N'")
    void shouldDenyWriteFileWhenRejected(@TempDir Path tempDir) {
        Path target = tempDir.resolve("denied.py");
        Scanner scanner = new Scanner(new ByteArrayInputStream("N\n".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String result = tools.writeFile(target.toString(), "print('Should not exist')");
        assertThat(result).isEqualTo("User denied file write.");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("Should search directory and return files containing the keyword")
    void shouldSearchDirectoryForKeyword(@TempDir Path tempDir) throws IOException {
        Path matchFile = tempDir.resolve("app.log");
        Files.writeString(matchFile, "Line 1\nTarget keyword CRITICAL_ERROR found\nLine 3");

        Path nonMatchFile = tempDir.resolve("other.log");
        Files.writeString(nonMatchFile, "Line 1\nAll clear\nLine 3");

        Path binaryFile = tempDir.resolve("image.png");
        Files.writeString(binaryFile, "CRITICAL_ERROR in binary");

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String result = tools.searchDirectory(tempDir.toString(), "CRITICAL_ERROR");
        assertThat(result).contains("app.log");
        assertThat(result).doesNotContain("other.log");
        assertThat(result).doesNotContain("image.png");
    }

    @Test
    @DisplayName("Should read specific range of lines with line numbers prepended")
    void shouldReadFileLinesWithLineNumbers(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("test.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 50; i++) {
            sb.append("This is line ").append(i).append("\n");
        }
        Files.writeString(logFile, sb.toString());

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String result = tools.readFileLines(logFile.toString(), 30, 35);
        assertThat(result).contains("30: This is line 30");
        assertThat(result).contains("35: This is line 35");
        assertThat(result).doesNotContain("29: This is line 29");
        assertThat(result).doesNotContain("36: This is line 36");
    }

    @Test
    @DisplayName("Should reject line range exceeding 200 lines")
    void shouldRejectLineRangeExceeding200(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("large.log");
        Files.writeString(logFile, "sample content");

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalTools tools = new TerminalTools(scanner);

        String result = tools.readFileLines(logFile.toString(), 1, 202);
        assertThat(result).isEqualTo("Error: Line range too large. Request 200 lines or fewer.");
    }
}
