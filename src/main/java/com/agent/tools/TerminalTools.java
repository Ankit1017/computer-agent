package com.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Host operating system terminal and filesystem tools for the autonomous agent.
 * Equipped with a Human-In-The-Loop (HITL) security confirmation prompt for destructive actions.
 */
public class TerminalTools {

    private static final Logger log = LoggerFactory.getLogger(TerminalTools.class);
    private static final int MAX_FILE_READ_CHARACTERS = 3000;
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    private final Scanner consoleScanner;

    public TerminalTools(Scanner consoleScanner) {
        this.consoleScanner = Objects.requireNonNull(consoleScanner, "consoleScanner must not be null");
    }

    /**
     * Executes a raw terminal command on the host OS after obtaining explicit human confirmation.
     *
     * @param command shell command to execute
     * @return combined process output, or denial message
     */
    @Tool("Execute a raw terminal command on the host OS. Returns the standard output or error.")
    public String executeCommand(String command) {
        if (command == null || command.isBlank()) {
            return "Error: Command cannot be empty.";
        }

        log.warn("HITL Intercept: Agent requested command execution: '{}'", command);

        System.out.println("\n[SECURITY] Agent requested to run: " + command);
        System.out.print("Allow execution? (Y/N): ");
        System.out.flush();

        String confirmation = "";
        if (consoleScanner.hasNextLine()) {
            confirmation = consoleScanner.nextLine().trim();
        }

        if (!"Y".equalsIgnoreCase(confirmation)) {
            log.info("HITL Intercept: User rejected command execution ('{}')", confirmation);
            return "User denied execution.";
        }

        log.info("HITL Intercept: User approved command execution. Spawning process...");

        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder processBuilder = isWindows
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("bash", "-c", command);

            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Command execution timed out after " + PROCESS_TIMEOUT_SECONDS + " seconds.";
            }

            int exitCode = process.exitValue();
            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "[Process completed with exit code " + exitCode + ", no output]";
            }
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Process execution interrupted: " + e.getMessage();
        } catch (Exception e) {
            log.error("Failed to execute command: " + command, e);
            return "Error: Execution failed: " + e.getMessage();
        }
    }

    /**
     * Lists all files and folders in a given directory path.
     *
     * @param path target directory path (relative or absolute)
     * @return formatted listing of directory contents
     */
    @Tool("List all files and folders in a given directory path.")
    public String listDirectory(String path) {
        if (path == null || path.isBlank()) {
            path = ".";
        }

        try {
            Path targetPath = Paths.get(path);
            if (!Files.exists(targetPath)) {
                return "Error: Path does not exist: " + path;
            }
            if (!Files.isDirectory(targetPath)) {
                return "Error: Path is not a directory: " + path;
            }

            try (Stream<Path> stream = Files.list(targetPath)) {
                String listing = stream
                        .map(p -> {
                            String name = p.getFileName().toString();
                            return Files.isDirectory(p) ? "[DIR]  " + name : "[FILE] " + name;
                        })
                        .sorted()
                        .collect(Collectors.joining(System.lineSeparator()));

                return listing.isEmpty() ? "[Directory is empty]" : listing;
            }
        } catch (Exception e) {
            log.error("Failed to list directory: " + path, e);
            return "Error: Could not list directory: " + e.getMessage();
        }
    }

    /**
     * Reads the string contents of a text file, truncating output if it exceeds safety limits.
     *
     * @param path target file path
     * @return contents of the file, truncated if exceeding character limit
     */
    @Tool("Read the string contents of a text file.")
    public String readFile(String path) {
        if (path == null || path.isBlank()) {
            return "Error: File path cannot be empty.";
        }

        try {
            Path targetPath = Paths.get(path);
            if (!Files.exists(targetPath)) {
                return "Error: File does not exist: " + path;
            }
            if (Files.isDirectory(targetPath)) {
                return "Error: Path is a directory, not a file: " + path;
            }

            String content = Files.readString(targetPath, StandardCharsets.UTF_8);
            if (content.length() > MAX_FILE_READ_CHARACTERS) {
                return content.substring(0, MAX_FILE_READ_CHARACTERS) + "\n...[truncated]";
            }
            return content;

        } catch (IOException e) {
            log.error("Failed to read file: " + path, e);
            return "Error: Could not read file: " + e.getMessage();
        }
    }
}
