package com.agent.tools.terminal.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service encapsulating Java NIO filesystem operations: reading, writing, searching, and directory mapping.
 */
public class FileSystemService {

    private static final Logger log = LoggerFactory.getLogger(FileSystemService.class);
    private static final int MAX_FILE_READ_CHARACTERS = 3000;
    private static final int MAX_SEARCH_DEPTH = 3;
    private static final int MAX_SEARCH_MATCHES = 20;
    private static final int MAX_LINE_RANGE = 200;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
            ".exe", ".dll", ".so", ".dylib", ".bin", ".zip", ".tar", ".gz",
            ".pdf", ".woff", ".woff2", ".ttf", ".eot"
    );

    /**
     * Recursively lists the contents of a directory up to depth 4, ignoring build and hidden folders.
     *
     * @param directoryPath directory path to list
     * @return formatted project structure or error
     */
    public String listDirectory(String directoryPath) {
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
                    .filter(path -> !path.contains(".git")
                            && !path.contains("target")
                            && !path.contains(".idea")
                            && !path.contains("node_modules")
                            && !path.contains("build"))
                    .limit(100)
                    .toList();

            for (String file : files) {
                sb.append(file).append("\n");
            }

            if (files.size() == 100) {
                sb.append("... [DIRECTORY TRUNCATED TO PRESERVE CONTEXT. BE MORE SPECIFIC] ...\n");
            }
        } catch (IOException e) {
            log.error("Failed to list directory: " + directoryPath, e);
            return "Error reading directory: " + e.getMessage();
        }
        return sb.toString();
    }

    /**
     * Reads the entire string content of a text file, truncating output if it exceeds character limits.
     *
     * @param path target file path
     * @return file content string
     */
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

    /**
     * Reads a specific range of lines from a file with line numbers prepended.
     *
     * @param path      path to file
     * @param startLine 1-based start line index
     * @param endLine   1-based end line index
     * @return formatted lines or error message
     */
    public String readFileLines(String path, Integer startLine, Integer endLine) {
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

        log.info("FileSystemService: readFileLines(path='{}', startLine={}, endLine={})", path, start, end);

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
     * Searches all text files in a directory for a keyword up to depth 3.
     *
     * @param directoryPath target directory to search
     * @param keyword       search keyword
     * @return matching file paths
     */
    public String searchDirectory(String directoryPath, String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "Error: You must provide a specific keyword to search for. If you just want to see the files, use listDirectory instead.";
        }
        if (directoryPath == null || directoryPath.isBlank()) {
            directoryPath = ".";
        }

        log.info("FileSystemService: searchDirectory(directoryPath='{}', keyword='{}')", directoryPath, keyword);

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
     * Writes or overwrites a file with content.
     *
     * @param path    target file path
     * @param content text content to write
     * @return confirmation message
     */
    public String writeFile(String path, String content) {
        if (path == null || path.isBlank()) {
            return "Error: File path cannot be empty.";
        }

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
     * Replaces targeted lines in an existing file with replacement content.
     *
     * @param path       target file path
     * @param startLine  1-based start line index
     * @param endLine    1-based end line index
     * @param newContent content to insert
     * @return confirmation message
     */
    public String replaceLines(String path, Integer startLine, Integer endLine, String newContent) {
        if (path == null || path.isBlank()) {
            return "Error: File path cannot be empty.";
        }
        if (startLine == null || endLine == null) {
            return "Error: startLine and endLine must not be null.";
        }
        if (startLine < 1 || endLine < startLine) {
            return "Error: Invalid line range: startLine=" + startLine + ", endLine=" + endLine;
        }

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
