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
            return "User denied execution.";
        }
        return commandService.execute(command);
    }

    @Tool("Recursively list directory contents as a formatted tree. Use excludeRegex to filter noise, and maxDepth to control traversal depth. Leave excludeRegex blank for smart default filtering (.git, target, node_modules, .idea, build, dist, out, venv).")
    public String listDirectory(
            @P("The absolute or relative path to the directory") String directoryPath,
            @P("Optional Regex to exclude file/folder paths. Leave blank for smart default noise filtering.") String excludeRegex,
            @P("Maximum depth to traverse (e.g. 1, 2, 4). Leave null or 0 to use default depth of 4.") Integer maxDepth) {
        int depth = (maxDepth != null && maxDepth > 0) ? maxDepth : 4;
        return fileService.listDirectory(directoryPath, excludeRegex, depth);
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

    @Tool("Recursively search directory files for a regex or keyword. Provides exact line numbers of matches.")
    public String searchDirectory(
            @P("The path to the directory") String directoryPath,
            @P("The keyword or Regex pattern to search for") String keyword,
            @P("Optional file extension to filter by (e.g., '.java' or '.md'). Leave blank for all text files.") String fileExtension) {
        return fileService.searchDirectory(directoryPath, keyword, fileExtension);
    }

    @Tool("Read specific lines from a text file with line numbers. STRICT LIMIT: Max 500 lines per read. Automatically rejects binary files. Use this for safe, paginated file inspection.")
    public String readFileLines(
            @P("The path to the file") String filePath,
            @P("The starting line number (1-indexed)") int startLine,
            @P("The ending line number (inclusive). Max 500 lines above startLine.") int endLine) {
        return fileService.readFileLines(filePath, startLine, endLine);
    }

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

        String details = "lines " + startLine + " to " + endLine + " in: " + path;
        if (!security.requestPermission("patch", details)) {
            return "User denied file patch.";
        }
        return fileService.replaceLines(path, startLine, endLine, newContent);
    }
}
