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

    @Tool("Recursively list directory contents as a formatted tree (depth 4). Use excludeRegex to filter out noisy folders. Leave excludeRegex blank to apply the default noise filter (.git, target, node_modules, .idea, build, dist, out, venv).")
    public String listDirectory(
            @P("The absolute or relative path to the directory") String directoryPath,
            @P("Optional Regex to exclude file/folder paths. Leave blank for smart default noise filtering.") String excludeRegex) {
        return fileService.listDirectory(directoryPath, excludeRegex);
    }

    @Tool("Read the string contents of a text file.")
    public String readFile(@P("path to file") String path) {
        return fileService.readFile(path);
    }

    @Tool("Write or overwrite a file with the provided string content.")
    public String writeFile(@P("target file path") String path, @P("file content to write") String content) {
        if (path == null || path.isBlank()) {
            return "Error: File path cannot be empty.";
        }
        if (!security.requestPermission("write file at", path)) {
            return "User denied file write.";
        }
        return fileService.writeFile(path, content);
    }

    @Tool("Search all text files in a directory for a specific keyword. Returns a list of files containing the keyword.")
    public String searchDirectory(
            @P("directory path to search") String directoryPath,
            @P("keyword to search for within text files") String keyword) {
        return fileService.searchDirectory(directoryPath, keyword);
    }

    @Tool("Read a specific range of lines from a file. Use this to inspect large files safely.")
    public String readFileLines(
            @P("path to file") String path,
            @P("1-based start line number") Integer startLine,
            @P("1-based end line number") Integer endLine) {
        return fileService.readFileLines(path, startLine, endLine);
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
