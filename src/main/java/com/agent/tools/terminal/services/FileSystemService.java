package com.agent.tools.terminal.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
            ".exe", ".dll", ".so", ".dylib", ".bin", ".zip", ".tar", ".gz",
            ".pdf", ".woff", ".woff2", ".ttf", ".eot"
    );

    /**
     * Recursively lists the contents of a directory as a formatted tree up to a configurable depth.
     * Applies a Regex pattern to exclude noisy directories such as build artifacts and hidden folders.
     *
     * @param directoryPath directory path to walk
     * @param excludeRegex  regex matched against full path strings to exclude entries;
     *                      if null or blank, defaults to common build/hidden folder patterns
     * @param maxDepth      maximum traversal depth (must be >= 1)
     * @return tree-formatted project structure or error
     */
    public String listDirectory(String directoryPath, String excludeRegex, int maxDepth) {
        if (directoryPath == null || directoryPath.isBlank()) {
            directoryPath = ".";
        }
        Path start = Paths.get(directoryPath).toAbsolutePath().normalize();
        if (!Files.exists(start)) {
            return "Error: Directory does not exist.";
        }

        // Default aggressive filter for common noise directories
        String regex = (excludeRegex == null || excludeRegex.isBlank())
                ? "(?i).*(\\.git|target|node_modules|\\.idea|build|dist|out|venv|__pycache__).*"
                : excludeRegex;

        int depthLimit = maxDepth <= 0 ? 1 : maxDepth;
        StringBuilder sb = new StringBuilder("Directory Tree for " + directoryPath + ":\n");
        try (Stream<Path> stream = Files.walk(start, depthLimit)) {
            List<Path> entries = stream
                    .filter(path -> !path.toString().replace("\\", "/").matches(regex))
                    .limit(150)
                    .toList();

            for (Path path : entries) {
                if (path.equals(start)) {
                    sb.append(path.getFileName() != null ? path.getFileName().toString() : path.toString()).append("/\n");
                    continue;
                }
                int depth = start.relativize(path).getNameCount();
                String prefix = "  ".repeat(depth - 1) + "|-- ";
                sb.append(prefix).append(path.getFileName() != null ? path.getFileName().toString() : path.toString());
                if (Files.isDirectory(path)) {
                    sb.append("/");
                }
                sb.append("\n");
            }

            if (entries.size() == 150) {
                sb.append("... [DIRECTORY TRUNCATED AT 150 ITEMS TO PRESERVE CONTEXT] ...\n");
            }
        } catch (IOException e) {
            log.error("Failed to list directory: {}", directoryPath, e);
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
     * Reads a specific range of lines from a text file with binary detection, UTF-8 validation,
     * and strict 500-line pagination to protect the LLM context window.
     *
     * @param filePath  path to the file
     * @param startLine 1-based start line index
     * @param endLine   1-based end line index (inclusive)
     * @return formatted numbered lines or descriptive error message
     */
    public String readFileLines(String filePath, int startLine, int endLine) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: File path cannot be empty.";
        }

        Path path = resolveFilePath(filePath);
        if (!Files.exists(path)) {
            return "Error: File does not exist.";
        }
        if (Files.isDirectory(path)) {
            return "Error: Path is a directory, not a file.";
        }

        // 1. Binary Safeguard: probe first 1024 bytes for null characters (0x00)
        if (isBinaryFile(path)) {
            return "Error: File appears to be binary. Cannot read contents into context.";
        }

        // 2. Pagination limits
        if (startLine < 1) startLine = 1;
        if (endLine < startLine) {
            return "Error: endLine must be greater than or equal to startLine.";
        }
        if ((endLine - startLine) > 500) {
            return "Error: Cannot read more than 500 lines at a time. Please paginate your request to protect the context window.";
        }

        // 3. UTF-8 streaming read
        StringBuilder sb = new StringBuilder("File: " + filePath + " (Lines " + startLine + " to " + endLine + ")\n");
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            final int finalStart = startLine;
            List<String> extracted = lines
                    .skip(startLine - 1)
                    .limit((long) endLine - startLine + 1)
                    .toList();

            if (extracted.isEmpty()) {
                return sb.append("[No lines found in this range]").toString();
            }
            for (int i = 0; i < extracted.size(); i++) {
                sb.append(finalStart + i).append(": ").append(extracted.get(i)).append("\n");
            }
        } catch (java.nio.charset.MalformedInputException e) {
            return "Error: File is not valid UTF-8. It may be binary or use a non-standard encoding.";
        } catch (IOException e) {
            log.error("Failed to read file lines: {}", filePath, e);
            return "Error reading file: " + e.getMessage();
        }

        return sb.toString();
    }

    /**
     * Recursively searches directory files for a regex or keyword pattern.
     * Enforces file extension filtering, ignores binary files and common noisy build folders,
     * and caps global matches at 50 to protect context window limits.
     *
     * @param directoryPath path to directory
     * @param keyword       regex or literal keyword
     * @param fileExtension optional extension filter (e.g. ".java"), or null/blank for all text files
     * @return formatted search results with file names and matched line numbers
     */
    public String searchDirectory(String directoryPath, String keyword, String fileExtension) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return "Error: You must provide a specific keyword/regex to search for.";
        }

        if (directoryPath == null || directoryPath.isBlank()) {
            directoryPath = ".";
        }

        Path start = Paths.get(directoryPath).toAbsolutePath().normalize();
        if (!Files.exists(start)) return "Error: Directory does not exist.";
        if (!Files.isDirectory(start)) return "Error: Path is not a directory: " + directoryPath;

        // Compile regex, fallback to literal match if invalid regex provided
        Pattern pattern;
        try {
            pattern = Pattern.compile(keyword, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            pattern = Pattern.compile(Pattern.quote(keyword), Pattern.CASE_INSENSITIVE);
        }

        StringBuilder sb = new StringBuilder("Search Results for '" + keyword + "' in " + directoryPath + ":\n");
        int globalMatchCount = 0;

        // AST Parsing Hook Stub
        // if (".java".equalsIgnoreCase(fileExtension)) { 
        //    // Future integration: Route to JavaParserService for semantic AST search
        // }

        try (Stream<Path> stream = Files.walk(start)) {
            List<Path> files = stream
                .filter(Files::isRegularFile)
                .filter(path -> !path.toString().replace("\\", "/").matches("(?i).*(\\.git|target|node_modules|\\.idea|build|dist|out|venv|__pycache__).*"))
                .filter(path -> fileExtension == null || fileExtension.isBlank() || path.toString().endsWith(fileExtension))
                .toList();

            for (Path path : files) {
                if (globalMatchCount >= 50) break;

                // Binary Safeguard (Reuse 1024-byte probe logic)
                if (isBinaryFile(path)) continue; 

                List<String> fileMatches = new ArrayList<>();
                try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
                    int[] lineNumber = {1};
                    Pattern finalPattern = pattern;
                    lines.forEach(line -> {
                        if (finalPattern.matcher(line).find()) {
                            fileMatches.add("  Line " + lineNumber[0] + ": " + line.trim());
                        }
                        lineNumber[0]++;
                    });
                } catch (Exception ignored) {
                    // Skip files that fail UTF-8 decoding during line streaming
                }

                if (!fileMatches.isEmpty()) {
                    sb.append("\nFile: ").append(start.relativize(path)).append("\n");
                    for (String match : fileMatches) {
                        if (globalMatchCount >= 50) break;
                        sb.append(match).append("\n");
                        globalMatchCount++;
                    }
                }
            }

            if (globalMatchCount == 0) {
                sb.append("\nNo matches found.");
            } else if (globalMatchCount >= 50) {
                sb.append("\n... [SEARCH TRUNCATED AT 50 MATCHES TO PRESERVE CONTEXT] ...");
            }
        } catch (Exception e) {
            return "Error searching directory: " + e.getMessage();
        }

        return sb.toString();
    }

    private boolean isBinaryFile(Path path) {
        try (java.io.InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024];
            int bytesRead = is.read(buffer);
            if (bytesRead > 0) {
                for (int i = 0; i < bytesRead; i++) {
                    if (buffer[i] == 0x00) return true;
                }
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    private Path resolveFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return Paths.get(".");
        }
        Path path = Paths.get(filePath);
        if (path.getFileName() == null) {
            return path;
        }
        if (!Files.exists(path)) {
            try (Stream<Path> search = Files.walk(Paths.get("."))) {
                java.util.Optional<Path> found = search
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase(path.getFileName().toString()))
                        .filter(p -> {
                            String s = p.toString().replace("\\", "/");
                            return !s.contains("/target/") && !s.contains("/.git/") && !s.contains("/.idea/");
                        })
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            } catch (Exception ignored) {
            }
        }
        return path;
    }

    /**
     * Writes or overwrites a file with content, creating an automatic .bak backup if the file already exists,
     * and enforcing a strict 5MB payload limit.
     *
     * @param filePath target file path
     * @param content  text content to write
     * @return confirmation message
     */
    public String writeFile(String filePath, String content) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: File path cannot be empty.";
        }
        if (content == null) {
            content = "";
        }

        Path path = Paths.get(filePath);

        // 1. Payload Size Guardrail (5MB limit)
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > 5 * 1024 * 1024) {
            return "Error: Payload exceeds maximum write limit of 5MB.";
        }

        try {
            // 2. Automatic Backup Generation
            if (Files.exists(path)) {
                Path backupPath = Paths.get(filePath + ".bak");
                Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[FILESYSTEM] Created backup: " + backupPath.toString());
            } else {
                // Ensure parent directories exist for new files
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
            }

            // 3. Atomic/UTF-8 Write
            Files.write(path, contentBytes, 
                StandardOpenOption.CREATE, 
                StandardOpenOption.TRUNCATE_EXISTING);
                
        } catch (IOException e) {
            return "Error writing file: " + e.getMessage();
        }

        return "Successfully wrote " + contentBytes.length + " bytes to " + filePath + 
               (Files.exists(Paths.get(filePath + ".bak")) ? " (Original backed up to .bak)" : "");
    }

    /**
     * Replaces specific lines in a file, creating an automatic .bak backup.
     *
     * @param filePath  target file path
     * @param startLine 1-based start line index
     * @param endLine   1-based end line index (inclusive)
     * @param content   replacement string content
     * @return confirmation or error message
     */
    public String replaceLines(String filePath, int startLine, int endLine, String content) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: File path cannot be empty.";
        }
        Path path = resolveFilePath(filePath);
        if (!Files.exists(path)) return "Error: File does not exist.";
        if (Files.isDirectory(path)) return "Error: Path is a directory, not a file.";

        try {
            // Automatic Backup
            Path backupPath = Paths.get(filePath + ".bak");
            Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);

            List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));

            // Bounds checking
            if (startLine < 1) startLine = 1;
            if (endLine > lines.size()) endLine = lines.size();
            if (startLine > endLine || startLine > lines.size()) {
                return "Error: Invalid line ranges. File only has " + lines.size() + " lines.";
            }

            // Remove old lines
            lines.subList(startLine - 1, endLine).clear();

            // Insert new lines
            if (content != null && !content.isEmpty()) {
                String[] newLines = content.split("\r?\n");
                lines.addAll(startLine - 1, Arrays.asList(newLines));
            }

            Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            return "Successfully replaced lines " + startLine + " to " + endLine + ". Original backed up to .bak";

        } catch (Exception e) {
            return "Error replacing lines: " + e.getMessage();
        }
    }

    /**
     * Searches for a Regex pattern in a file and replaces matches, creating an automatic .bak backup.
     * Supports MULTILINE and DOTALL flags.
     *
     * @param filePath    target file path
     * @param regex       regular expression pattern to search for
     * @param replacement replacement text
     * @return confirmation or error message
     */
    public String regexReplaceInFile(String filePath, String regex, String replacement) {
        if (filePath == null || filePath.isBlank()) {
            return "Error: File path cannot be empty.";
        }
        if (regex == null || regex.isEmpty()) {
            return "Error: Regex pattern cannot be empty.";
        }
        Path path = resolveFilePath(filePath);
        if (!Files.exists(path)) return "Error: File does not exist.";
        if (Files.isDirectory(path)) return "Error: Path is a directory, not a file.";

        try {
            // Automatic Backup
            Path backupPath = Paths.get(filePath + ".bak");
            Files.copy(path, backupPath, StandardCopyOption.REPLACE_EXISTING);

            String fileContent = Files.readString(path, StandardCharsets.UTF_8);

            Pattern pattern;
            try {
                // MULTILINE allows ^ and $ to match start/end of lines, DOTALL allows . to match newlines
                pattern = Pattern.compile(regex, Pattern.MULTILINE | Pattern.DOTALL);
            } catch (PatternSyntaxException e) {
                return "Error: Invalid regex pattern provided.";
            }

            Matcher matcher = pattern.matcher(fileContent);
            if (!matcher.find()) {
                return "Error: Regex pattern did not match any content in the file. Patch aborted.";
            }

            String updatedContent = matcher.replaceAll(Matcher.quoteReplacement(replacement != null ? replacement : ""));

            Files.writeString(path, updatedContent, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            return "Successfully applied regex replacement. Original backed up to .bak";

        } catch (Exception e) {
            return "Error applying regex patch: " + e.getMessage();
        }
    }
}
