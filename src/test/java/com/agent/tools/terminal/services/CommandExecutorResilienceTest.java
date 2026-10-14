package com.agent.tools.terminal.services;

import com.agent.tools.terminal.TerminalToolsFacade;
import com.agent.tools.terminal.security.SecurityInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

class CommandExecutorResilienceTest {

    private final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    @Test
    @DisplayName("Should enforce process tree timeout and terminate all descendant process handles")
    void shouldEnforceProcessTreeTimeoutAndKillDescendants() throws Exception {
        // Configure a short 1-second timeout
        CommandExecutorService executor = new CommandExecutorService(1, 100_000);

        // Command that attempts to sleep for 10 seconds (spawns child process under cmd.exe/sh)
        String sleepCommand = isWindows
                ? "powershell -NoProfile -Command \"Start-Sleep -Seconds 10\""
                : "sleep 10";

        long start = System.currentTimeMillis();
        String result = executor.executeCommand(sleepCommand);
        long elapsed = System.currentTimeMillis() - start;

        // Verify prompt return within timeout boundary (< 4000ms including virtual thread teardown)
        assertThat(elapsed).isLessThan(4000);
        assertThat(result).contains("Error: Command timed out after 1 seconds.");

        // Verify process tree was forcibly destroyed
        ProcessHandle rootHandle = executor.getLastExecutedProcessHandle();
        assertThat(rootHandle).isNotNull();

        // Allow up to 1500ms for OS process table to register forcible termination
        long deadline = System.currentTimeMillis() + 1500;
        while ((rootHandle.isAlive() || rootHandle.descendants().anyMatch(ProcessHandle::isAlive))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(rootHandle.isAlive()).isFalse();
        assertThat(rootHandle.descendants().filter(ProcessHandle::isAlive)).isEmpty();
    }

    @Test
    @DisplayName("Should bound captured output, append truncation marker, and drain OS pipe without deadlock")
    void shouldBoundOutputAndDrainPipeWithoutDeadlock() {
        // Max output limit of 200 characters
        int maxChars = 200;
        CommandExecutorService executor = new CommandExecutorService(Paths.get("."), 15, maxChars);

        // Generates ~3,000 to 5,000 characters of text
        String largeOutputCommand = isWindows
                ? "powershell -NoProfile -Command \"1..200 | ForEach-Object { 'Data-Line-' + $_ + '-FillerContent' }\""
                : "sh -c 'for i in $(seq 1 200); do echo \"Data-Line-$i-FillerContent\"; done'";

        String result = executor.executeCommand(largeOutputCommand);

        // Subprocess should exit cleanly with exit code 0 because stream draining prevented pipe deadlock
        assertThat(result).contains("Exit Code: 0");
        assertThat(result).contains(CommandExecutorService.TRUNCATION_MARKER.trim());

        // Extract captured stdout
        int outputStart = result.indexOf("Output:\n");
        int truncStart = result.indexOf("...[OUTPUT TRUNCATED]...");
        assertThat(outputStart).isGreaterThanOrEqualTo(0);
        assertThat(truncStart).isGreaterThan(outputStart);

        String stdoutCaptured = result.substring(outputStart + "Output:\n".length(), truncStart).trim();
        // The captured text length must not exceed maxChars + small margin for line endings
        assertThat(stdoutCaptured.length()).isLessThanOrEqualTo(maxChars + 10);
    }

    @Test
    @DisplayName("Should execute command inside custom working directory")
    void shouldExecuteInCustomWorkingDirectory(@TempDir Path tempDir) throws IOException {
        Path markerFile = tempDir.resolve("evidence.txt");
        Files.writeString(markerFile, "sandboxed_file_content_token_123");

        CommandExecutorService executor = new CommandExecutorService();
        String catCommand = isWindows ? "type evidence.txt" : "cat evidence.txt";

        String result = executor.execute(catCommand, tempDir);

        assertThat(result).contains("Exit Code: 0");
        assertThat(result).contains("sandboxed_file_content_token_123");
    }

    @Test
    @DisplayName("Should return graceful descriptive error when working directory does not exist")
    void shouldReturnGracefulErrorWhenDirectoryDoesNotExist(@TempDir Path tempDir) {
        CommandExecutorService executor = new CommandExecutorService();
        Path nonExistent = tempDir.resolve("missing_folder_abc");

        String result = executor.execute("echo test", nonExistent);

        assertThat(result).startsWith("Error: Working directory does not exist:");
        assertThat(result).contains("missing_folder_abc");
    }

    @Test
    @DisplayName("Should return graceful descriptive error when working directory is a regular file")
    void shouldReturnGracefulErrorWhenDirectoryIsAFile(@TempDir Path tempDir) throws IOException {
        Path fileAsDir = tempDir.resolve("sample.txt");
        Files.writeString(fileAsDir, "not a directory");

        CommandExecutorService executor = new CommandExecutorService();
        String result = executor.execute("echo test", fileAsDir);

        assertThat(result).startsWith("Error: Working directory is not a directory:");
        assertThat(result).contains("sample.txt");
    }

    @Test
    @DisplayName("Should enforce sandbox root boundary when configured")
    void shouldEnforceSandboxRootBoundary(@TempDir Path tempDir) throws IOException {
        Path sandboxDir = tempDir.resolve("isolated_sandbox");
        Files.createDirectories(sandboxDir);

        // Restrict executor to sandboxDir
        CommandExecutorService sandboxedExecutor = new CommandExecutorService(sandboxDir, sandboxDir, 10, 100_000);

        // Attempt to execute in the parent tempDir (outside sandbox)
        String result = sandboxedExecutor.execute("echo test", tempDir);

        assertThat(result).startsWith("Error: Working directory is outside sandbox root:");
    }

    @Test
    @DisplayName("Should expose overloaded executeCommand in TerminalToolsFacade with security gate")
    void shouldSupportTerminalToolsFacadeOverloadWithSecurityGate(@TempDir Path tempDir) throws IOException {
        Path markerFile = tempDir.resolve("facade_evidence.txt");
        Files.writeString(markerFile, "facade_payload_verified");

        CommandExecutorService executor = new CommandExecutorService();
        String catCommand = isWindows ? "type facade_evidence.txt" : "cat facade_evidence.txt";

        // 1. Authorized execution
        Scanner allowScanner = new Scanner(new ByteArrayInputStream("y\n".getBytes()));
        SecurityInterceptor allowSecurity = new SecurityInterceptor(allowScanner);
        TerminalToolsFacade facade = new TerminalToolsFacade(allowSecurity, executor, new FileSystemService());

        String allowedResult = facade.executeCommand(catCommand, tempDir.toString());
        assertThat(allowedResult).contains("Exit Code: 0");
        assertThat(allowedResult).contains("facade_payload_verified");

        // 2. Denied execution
        Scanner denyScanner = new Scanner(new ByteArrayInputStream("n\n".getBytes()));
        SecurityInterceptor denySecurity = new SecurityInterceptor(denyScanner);
        TerminalToolsFacade denyFacade = new TerminalToolsFacade(denySecurity, executor, new FileSystemService());

        String deniedResult = denyFacade.executeCommand(catCommand, tempDir.toString());
        assertThat(deniedResult).isEqualTo("User denied command execution.");
    }
}
