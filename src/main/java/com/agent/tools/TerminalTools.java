package com.agent.tools;

import dev.langchain4j.agent.tool.P;
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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;
import java.util.Set;
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
    private static final int MAX_SEARCH_DEPTH = 3;
    private static final int MAX_SEARCH_MATCHES = 20;
    private static final int MAX_LINE_RANGE = 200;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
            ".exe", ".dll", ".so", ".dylib", ".bin", ".zip", ".tar", ".gz",
            ".pdf", ".woff", ".woff2", ".ttf", ".eot"
    );

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
    public String executeCommand(@P("command to execute") String command) {
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
    @Tool("Recursively list the contents of a directory to understand project structure. Automatically ignores noisy folders like .git, target, and node_modules.")
    public String listDirectory(@P("The absolute or relative path to the directory") String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            directoryPath = ".";
        }
        Path start = Paths.get(directoryPath);
        if (!Files.exists(start)) {
            return "Error: Directory does not exist.";
        }

        StringBuilder sb = new StringBuilder("Project Structure for " + directoryPath + ":\n");
        try (Stream<Path> stream = Files.walk(start, 4)) {
            List<String> files = stream
                    .map(Path::toString)
                    // Hard-exclude massive/hidden directories to preserve LLM context
                    .filter(path -> !path.contains(".git")
                            && !path.contains("target")
                            && !path.contains(".idea")
                            && !path.contains("node_modules")
                            && !path.contains("build"))
                    .limit(100) // Protect context window
                    .toList();

            for (String file : files) {
                sb.append(file).append("\n");
            }

            if (files.size() == 100) {
                sb.append("... [DIRECTORY TRUNCATED TO PRESERVE CONTEXT. BE MORE SPECIFIC] ...\n");
            }
        } catch (IOException e) {
            return "Error reading directory: " + e.getMessage();
        }
        return sb.toString();
    }

    /**
     * Reads the string contents of a text file, truncating output if it exceeds safety limits.
     *
     * @param path target file path
     * @return contents of the file, truncated if exceeding character limit
     */
    @Tool("Read the string contents of a text file.")
    public String readFile(@P("path to file") String path) {
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

    /**
     * Writes or overwrites a file with the provided string content after obtaining human confirmation.
     *
     * @param path    target file path
     * @param content text content to write
     * @return confirmation of successful write, or denial message
     */
    @Tool("Write or overwrite a file with the provided string content.")
    public String writeFile(@P("target file path") String path, @P("file content to write") String content) {
        if (path == null || path.isBlank()) {
            return "Error: File path cannot be empty.";
        }

        log.warn("HITL Intercept: Agent requested file write at: '{}'", path);

        System.out.println("\n[SECURITY] Agent requested to write file at: " + path);
        System.out.print("Allow write? (Y/N): ");
        System.out.flush();

        String confirmation = "";
        if (consoleScanner.hasNextLine()) {
            confirmation = consoleScanner.nextLine().trim();
        }

        if (!"Y".equalsIgnoreCase(confirmation)) {
            log.info("HITL Intercept: User rejected file write ('{}')", confirmation);
            return "User denied file write.";
        }

        log.info("HITL Intercept: User approved file write. Writing to disk...");

        try {
            Path targetPath = Paths.get(path);
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            Files.writeString(targetPath, content != null ? content : "",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "File written successfully to " + path;
        } catch (IOException e) {
            log.error("Failed to write file: " + path, e);
            return "Error: Could not write file: " + e.getMessage();
        }
    }

    /**
     * Searches all text files in a directory for a specific keyword up to depth 3.
     *
     * @param directoryPath target directory to search
     * @param keyword       string keyword to search for
     * @return formatted list of matching file paths
     */
    @Tool("Search all text files in a directory for a specific keyword. Returns a list of files containing the keyword.")
    public String searchDirectory(
            @P("directory path to search") String directoryPath,
            @P("keyword to search for within text files") String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "Error: You must provide a specific keyword to search for. If you just want to see the files, use listDirectory instead.";
        }
        if (directoryPath == null || directoryPath.isBlank()) {
            directoryPath = ".";
        }

        log.info("Tool Call: searchDirectory(directoryPath='{}', keyword='{}')", directoryPath, keyword);

        try {
            Path targetDir = Paths.get(directoryPath);
            if (!Files.exists(targetDir)) {
                return "Error: Directory path does not exist: " + directoryPath;
            }
            if (!Files.isDirectory(targetDir)) {
                return "Error: Path is not a directory: " + directoryPath;
            }

            try (Stream<Path> stream = Files.walk(targetDir, MAX_SEARCH_DEPTH)) {
                List<String> matches = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> {
                            String fileName = p.getFileName().toString().toLowerCase();
                            return BINARY_EXTENSIONS.stream().noneMatch(fileName::endsWith);
                        })
                        .filter(p -> {
                            try (Stream<String> lines = Files.lines(p, StandardCharsets.UTF_8)) {
                                return lines.anyMatch(line -> line.contains(keyword));
                            } catch (Exception ignored) {
                                return false;
                            }
                        })
                        .limit(MAX_SEARCH_MATCHES)
                        .map(Path::toString)
                        .collect(Collectors.toList());

                if (matches.isEmpty()) {
                    return "No text files found containing keyword: '" + keyword + "'";
                }

                return "Matching files found containing '" + keyword + "' (max " + MAX_SEARCH_MATCHES + "):\n"
                        + matches.stream().map(m -> "- " + m).collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.error("Failed to search directory: " + directoryPath, e);
            return "Error: Search failed: " + e.getMessage();
        }
    }

    /**
     * Reads a specific range of lines from a file with line numbers prepended.
     *
     * @param path      path to file
     * @param startLine 1-based start line index
     * @param endLine   1-based end line index
     * @return extracted lines with prepended line numbers, or error message
     */
    @Tool("Read a specific range of lines from a file. Use this to inspect large files safely.")
    public String readFileLines(
            @P("path to file") String path,
            @P("1-based start line number") Integer startLine,
            @P("1-based end line number") Integer endLine) {
        if (path == null || path.isBlank()) {
            return "Error: File path cannot be empty.";
        }
        int start = startLine != null ? startLine : 1;
        int end = endLine != null ? endLine : start + 50;
        if (start < 1 || end < start) {
            return "Error: Invalid line range: startLine=" + start + ", endLine=" + end;
        }
        if ((end - start) > MAX_LINE_RANGE) {
            return "Error: Line range too large. Request 200 lines or fewer.";
        }

        log.info("Tool Call: readFileLines(path='{}', startLine={}, endLine={})", path, start, end);

        try {
            Path targetPath = Paths.get(path);
            if (!Files.exists(targetPath)) {
                return "Error: File does not exist: " + path;
            }
            if (Files.isDirectory(targetPath)) {
                return "Error: Path is a directory, not a file: " + path;
            }

            try (Stream<String> lines = Files.lines(targetPath, StandardCharsets.UTF_8)) {
                List<String> slice = lines
                        .skip(start - 1)
                        .limit((long) end - start + 1)
                        .collect(Collectors.toList());

                if (slice.isEmpty()) {
                    return "[No lines found in requested range " + start + "-" + end + "]";
                }

                StringBuilder sb = new StringBuilder();
                int currentLine = start;
                for (String line : slice) {
                    sb.append(currentLine++).append(": ").append(line).append(System.lineSeparator());
                }
                return sb.toString().trim();
            }
        } catch (Exception e) {
            log.error("Failed to read file lines: " + path, e);
            return "Error: Could not read file lines: " + e.getMessage();
        }
    }

    /**
     * Replaces a specific range of lines in an existing file with new content after obtaining human confirmation.
     *
     * @param path       target file path
     * @param startLine  1-based start line index
     * @param endLine    1-based end line index
     * @param newContent new string content to insert at the targeted line range
     * @return confirmation of successful patch, or denial message
     */
    @Tool("Replace specific lines in an existing file with new content. Use this to patch code or text without rewriting the whole file.")
    public String replaceLinesInFile(
            @P("path to file") String path,
            @P("1-based start line number to replace") Integer startLine,
            @P("1-based end line number to replace") Integer endLine,
            @P("new content to insert") String newContent) {
        if (path == null || path.isBlank()) {
            return "Error: File path cannot be empty.";
        }
        if (startLine == null || endLine == null) {
            return "Error: startLine and endLine must not be null.";
        }
        if (startLine < 1 || endLine < startLine) {
            return "Error: Invalid line range: startLine=" + startLine + ", endLine=" + endLine;
        }

        log.warn("HITL Intercept: Agent requested patch on lines {}-{} in '{}'", startLine, endLine, path);

        System.out.println("\n[SECURITY] Agent requested to patch lines " + startLine + " to " + endLine + " in: " + path);
        System.out.print("Allow patch? (Y/N): ");
        System.out.flush();

        String confirmation = "";
        if (consoleScanner.hasNextLine()) {
            confirmation = consoleScanner.nextLine().trim();
        }

        if (!"Y".equalsIgnoreCase(confirmation)) {
            log.info("HITL Intercept: User rejected file patch ('{}')", confirmation);
            return "User denied file patch.";
        }

        log.info("HITL Intercept: User approved file patch. Applying patch to disk...");

        try {
            Path targetPath = Paths.get(path);
            if (!Files.exists(targetPath)) {
                return "Error: File does not exist: " + path;
            }
            if (Files.isDirectory(targetPath)) {
                return "Error: Path is a directory, not a file: " + path;
            }

            List<String> lines = new ArrayList<>(Files.readAllLines(targetPath, StandardCharsets.UTF_8));
            if (startLine > lines.size()) {
                return "Error: startLine " + startLine + " exceeds file line count (" + lines.size() + ")";
            }

            int effectiveEndLine = Math.min(endLine, lines.size());

            // Remove existing lines in range [startLine - 1, effectiveEndLine)
            lines.subList(startLine - 1, effectiveEndLine).clear();

            // Insert newContent at startLine - 1
            if (newContent != null && !newContent.isEmpty()) {
                String[] replacementLines = newContent.split("\\r?\\n", -1);
                for (int i = 0; i < replacementLines.length; i++) {
                    lines.add(startLine - 1 + i, replacementLines[i]);
                }
            }

            Files.write(targetPath, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return "Successfully patched lines " + startLine + " to " + endLine + " in " + path;

        } catch (IOException e) {
            log.error("Failed to patch file lines: " + path, e);
            return "Error: Could not patch file lines: " + e.getMessage();
        }
    }
}
