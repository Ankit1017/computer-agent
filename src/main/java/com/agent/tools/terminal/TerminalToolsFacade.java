package com.agent.tools.terminal;

import com.agent.tools.terminal.security.SecurityInterceptor;
import com.agent.tools.terminal.services.CommandExecutorService;
import com.agent.tools.terminal.services.FileSystemService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Objects;

/**
 * Enterprise Facade for terminal execution and file operations.
 * Holds LangChain4j tool annotations and delegates to underlying domain services and security interceptor.
 */
public class TerminalToolsFacade {

    private final SecurityInterceptor security;
    private final CommandExecutorService commandService;
    private final FileSystemService fileService;

    public TerminalToolsFacade(
            SecurityInterceptor security,
            CommandExecutorService commandService,
            FileSystemService fileService) {
        this.security = Objects.requireNonNull(security, "security interceptor must not be null");
        this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
        this.fileService = Objects.requireNonNull(fileService, "fileService must not be null");
    }

    @Tool("Execute a raw terminal command on the host OS. Returns the standard output or error.")
    public String executeCommand(@P("command to execute") String command) {
        if (command == null || command.isBlank()) {
            return "Error: Command cannot be empty.";
        }
        if (!security.requestPermission("execute command", command)) {
            return "User denied command execution.";
        }
        return commandService.execute(command);
    }

    // --- LIST DIRECTORY OVERLOADS ---
    @Tool("Recursively list directory contents as a tree using default settings.")
    public String listDirectory(@P("The path to the directory") String directoryPath) {
        return fileService.listDirectory(directoryPath, null, 4);
    }

    @Tool("Recursively list directory contents as a tree with a custom exclusion regex.")
    public String listDirectory(@P("The path to the directory") String directoryPath, @P("Regex to exclude paths") String excludeRegex) {
        return fileService.listDirectory(directoryPath, excludeRegex, 4);
    }

    @Tool("Recursively list directory contents as a tree with custom regex and maximum depth.")
    public String listDirectory(@P("The path to the directory") String directoryPath, @P("Regex to exclude paths") String excludeRegex, @P("Maximum depth to traverse") int maxDepth) {
        return fileService.listDirectory(directoryPath, excludeRegex, maxDepth);
    }

    @Tool("Read the string contents of a text file.")
    public String readFile(@P("path to file") String path) {
        return fileService.readFile(path);
    }

    @Tool("Create a new file or completely overwrite an existing one. If overwriting, an automatic .bak backup is created. Max payload: 5MB.")
    public String writeFile(
            @P("The path to the file") String filePath,
            @P("The complete text content to write") String content) {
        if (!security.requestPermission("write file at", filePath)) {
            return "User denied file write.";
        }
        return fileService.writeFile(filePath, content);
    }

    // --- SEARCH DIRECTORY OVERLOADS ---
    @Tool("Search directory files for a keyword or regex pattern across all text files.")
    public String searchDirectory(@P("The path to the directory") String directoryPath, @P("The keyword or Regex pattern to search for") String keyword) {
        return fileService.searchDirectory(directoryPath, keyword, null);
    }

    @Tool("Search directory files for a keyword or regex pattern, filtered by file extension.")
    public String searchDirectory(@P("The path to the directory") String directoryPath, @P("The keyword or Regex pattern to search for") String keyword, @P("File extension to filter by (e.g., '.java')") String fileExtension) {
        return fileService.searchDirectory(directoryPath, keyword, fileExtension);
    }

    @Tool("Read specific lines from a text file with line numbers. STRICT LIMIT: Max 500 lines per read. Automatically rejects binary files. Use this for safe, paginated file inspection.")
    public String readFileLines(
            @P("The path to the file") String filePath,
            @P("The starting line number (1-indexed)") int startLine,
            @P("The ending line number (inclusive). Max 500 lines above startLine.") int endLine) {
        return fileService.readFileLines(filePath, startLine, endLine);
    }

    @Tool("Replace specific lines in a file. An automatic .bak backup is created before patching.")
    public String replaceLinesInFile(
            @P("The path to the file") String filePath,
            @P("The starting line number (1-indexed)") int startLine,
            @P("The ending line number (inclusive)") int endLine,
            @P("The new content to insert") String content) {
        if (!security.requestPermission("replace lines in", filePath + " (" + startLine + "-" + endLine + ")")) {
            return "User denied line replacement.";
        }
        return fileService.replaceLines(filePath, startLine, endLine, content);
    }

    @Tool("Search for a Regex pattern in a file and replace it. Highly recommended over line-replacement to avoid line-shifting errors. An automatic .bak backup is created.")
    public String regexReplaceInFile(
            @P("The path to the file") String filePath,
            @P("The regex pattern to search for (supports MULTILINE and DOTALL)") String regex,
            @P("The new content to replace the matched regex with") String replacement) {
        if (!security.requestPermission("regex patch", filePath)) {
            return "User denied regex patch.";
        }
        return fileService.regexReplaceInFile(filePath, regex, replacement);
    }
}
