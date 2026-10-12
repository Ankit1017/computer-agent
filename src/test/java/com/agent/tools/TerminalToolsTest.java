package com.agent.tools;

import com.agent.tools.terminal.TerminalToolsFacade;
import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.terminal.services.CommandExecutorService;
import com.agent.tools.terminal.services.FileSystemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalToolsTest {

    private TerminalToolsFacade createTools(Scanner scanner) {
        return new TerminalToolsFacade(
                new SecurityInterceptor(scanner),
                new CommandExecutorService(),
                new FileSystemService()
        );
    }

    @Test
    @DisplayName("Should list files in an existing directory using default noise filter")
    void shouldListDirectory(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("sample1.txt"));
        Files.createFile(tempDir.resolve("sample2.txt"));

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String listing = tools.listDirectory(tempDir.toString(), "", null);
        assertThat(listing).contains("sample1.txt");
        assertThat(listing).contains("sample2.txt");
    }

    @Test
    @DisplayName("Should format directory listing as a tree with |-- prefixes")
    void shouldFormatDirectoryAsTree(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("src"));
        Files.createFile(tempDir.resolve("src").resolve("Main.java"));

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String listing = tools.listDirectory(tempDir.toString(), "", null);
        assertThat(listing).contains("|-- src");
        assertThat(listing).contains("|-- Main.java");
        assertThat(listing).startsWith("Directory Tree for");
    }

    @Test
    @DisplayName("Should exclude folders matching a custom regex pattern")
    void shouldExcludeFoldersMatchingCustomRegex(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("target"));
        Files.createFile(tempDir.resolve("target").resolve("app.jar"));
        Files.createFile(tempDir.resolve("README.md"));

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String listing = tools.listDirectory(tempDir.toString(), ".*target.*", null);
        assertThat(listing).contains("README.md");
        assertThat(listing).doesNotContain("app.jar");
    }

    @Test
    @DisplayName("Should respect maxDepth=1 and not recurse into subdirectories")
    void shouldRespectMaxDepthOne(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("subdir"));
        Files.createFile(tempDir.resolve("subdir").resolve("deep.txt"));
        Files.createFile(tempDir.resolve("root.txt"));

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String listing = tools.listDirectory(tempDir.toString(), "", 1);
        assertThat(listing).contains("root.txt");
        assertThat(listing).contains("subdir");
        assertThat(listing).doesNotContain("deep.txt");
    }

    @Test
    @DisplayName("Should read file content and truncate if exceeding 3000 characters")
    void shouldReadFileAndTruncate(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("large.txt");
        String largeContent = "A".repeat(4000);
        Files.writeString(file, largeContent);

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String readResult = tools.readFile(file.toString());
        assertThat(readResult).hasSizeLessThanOrEqualTo(3050);
        assertThat(readResult).contains("[truncated]");
    }

    @Test
    @DisplayName("Should execute terminal command when user approves with 'Y'")
    void shouldExecuteCommandWhenApproved() {
        Scanner scanner = new Scanner(new ByteArrayInputStream("Y\n".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.executeCommand("echo Antigravity");
        assertThat(result.trim()).contains("Antigravity");
    }

    @Test
    @DisplayName("Should deny execution and not run command when user enters 'N'")
    void shouldDenyCommandWhenRejected() {
        Scanner scanner = new Scanner(new ByteArrayInputStream("N\n".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.executeCommand("echo Antigravity");
        assertThat(result).isEqualTo("User denied execution.");
    }

    @Test
    @DisplayName("Should write file when user approves with 'Y'")
    void shouldWriteFileWhenApproved(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("output.py");
        Scanner scanner = new Scanner(new ByteArrayInputStream("Y\n".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.writeFile(target.toString(), "print('Hello World')");
        assertThat(result).contains("File written successfully");
        assertThat(Files.readString(target)).isEqualTo("print('Hello World')");
    }

    @Test
    @DisplayName("Should deny writing file when user enters 'N'")
    void shouldDenyWriteFileWhenRejected(@TempDir Path tempDir) {
        Path target = tempDir.resolve("denied.py");
        Scanner scanner = new Scanner(new ByteArrayInputStream("N\n".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.writeFile(target.toString(), "print('Should not exist')");
        assertThat(result).isEqualTo("User denied file write.");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("Should search directory and return files containing the keyword with line numbers")
    void shouldSearchDirectoryForKeyword(@TempDir Path tempDir) throws IOException {
        Path matchFile = tempDir.resolve("app.log");
        Files.writeString(matchFile, "Line 1\nTarget keyword CRITICAL_ERROR found\nLine 3");

        Path nonMatchFile = tempDir.resolve("other.log");
        Files.writeString(nonMatchFile, "Line 1\nAll clear\nLine 3");

        Path binaryFile = tempDir.resolve("image.png");
        byte[] binaryData = {0x00, 0x43, 0x52, 0x49, 0x54, 0x49, 0x43, 0x41, 0x4C, 0x5F, 0x45, 0x52, 0x52, 0x4F, 0x52};
        Files.write(binaryFile, binaryData);

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.searchDirectory(tempDir.toString(), "CRITICAL_ERROR", ".log");
        assertThat(result).contains("app.log");
        assertThat(result).contains("Line 2: Target keyword CRITICAL_ERROR found");
        assertThat(result).doesNotContain("other.log");
        assertThat(result).doesNotContain("image.png");
    }

    @Test
    @DisplayName("Should search directory using regex pattern and filter by file extension")
    void shouldSearchDirectoryWithRegexAndExtensionFilter(@TempDir Path tempDir) throws IOException {
        Path javaFile = tempDir.resolve("UserServiceFacade.java");
        Files.writeString(javaFile, "package com.example;\n\npublic class UserServiceFacade {\n}\n");

        Path txtFile = tempDir.resolve("notes.txt");
        Files.writeString(txtFile, "public class NoteServiceFacade is here\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        // Search for regex matching class definition, filtering specifically for .java
        String result = tools.searchDirectory(tempDir.toString(), "public class .*Facade", ".java");
        assertThat(result).contains("UserServiceFacade.java");
        assertThat(result).contains("Line 3: public class UserServiceFacade {");
        assertThat(result).doesNotContain("notes.txt");
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
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.readFileLines(logFile.toString(), 30, 35);
        assertThat(result).contains("30: This is line 30");
        assertThat(result).contains("35: This is line 35");
        assertThat(result).doesNotContain("29: This is line 29");
        assertThat(result).doesNotContain("36: This is line 36");
    }

    @Test
    @DisplayName("Should reject line range exceeding 500 lines")
    void shouldRejectLineRangeExceeding500(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("large.log");
        Files.writeString(logFile, "sample content");

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.readFileLines(logFile.toString(), 1, 502);
        assertThat(result).contains("Cannot read more than 500 lines");
    }

    @Test
    @DisplayName("Should reject binary files containing null bytes")
    void shouldRejectBinaryFilesWithNullBytes(@TempDir Path tempDir) throws IOException {
        Path binaryFile = tempDir.resolve("data.bin");
        byte[] binaryData = {0x50, 0x4B, 0x00, 0x04, 0x14, 0x00}; // PK\x00 ZIP magic
        Files.write(binaryFile, binaryData);

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.readFileLines(binaryFile.toString(), 1, 5);
        assertThat(result).contains("binary");
    }

    @Test
    @DisplayName("Should include file header with path and line range in readFileLines output")
    void shouldIncludeFileHeaderInReadFileLines(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("notes.txt");
        Files.writeString(logFile, "line one\nline two\nline three\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.readFileLines(logFile.toString(), 1, 2);
        assertThat(result).contains("File:");
        assertThat(result).contains("Lines 1 to 2");
        assertThat(result).contains("1: line one");
        assertThat(result).contains("2: line two");
    }

    @Test
    @DisplayName("Should surgically replace targeted lines when user approves with 'Y'")
    void shouldPatchFileLinesWhenApproved(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "server: localhost\nport: 8080\ndebug: false\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("Y\n".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.replaceLinesInFile(configFile.toString(), 2, 2, "port: 9090");
        assertThat(result).contains("Successfully patched lines 2 to 2");

        List<String> updatedLines = Files.readAllLines(configFile);
        assertThat(updatedLines).containsExactly("server: localhost", "port: 9090", "debug: false");
    }

    @Test
    @DisplayName("Should deny patching lines when user enters 'N'")
    void shouldDenyPatchWhenRejected(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "server: localhost\nport: 8080\ndebug: false\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("N\n".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.replaceLinesInFile(configFile.toString(), 2, 2, "port: 9090");
        assertThat(result).isEqualTo("User denied file patch.");

        List<String> unchangedLines = Files.readAllLines(configFile);
        assertThat(unchangedLines).containsExactly("server: localhost", "port: 8080", "debug: false");
    }

    @Test
    @DisplayName("Should replace line range with multi-line content")
    void shouldPatchMultipleLinesWithExpansion(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Files.writeString(configFile, "server: localhost\nport: 8080\ndebug: false\n");

        Scanner scanner = new Scanner(new ByteArrayInputStream("Y\n".getBytes()));
        TerminalToolsFacade tools = createTools(scanner);

        String result = tools.replaceLinesInFile(configFile.toString(), 2, 3, "port: 9090\nssl: true\ndebug: true");
        assertThat(result).contains("Successfully patched lines 2 to 3");

        List<String> updatedLines = Files.readAllLines(configFile);
        assertThat(updatedLines).containsExactly("server: localhost", "port: 9090", "ssl: true", "debug: true");
    }
}
