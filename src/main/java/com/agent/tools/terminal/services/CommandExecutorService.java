package com.agent.tools.terminal.services;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.*;

/**
 * Service encapsulating OS-level process execution with recursive process tree termination,
 * asynchronous non-blocking stream gobbling, bounded output capture, and configurable sandbox working directories.
 */
public class CommandExecutorService {

    public static final long DEFAULT_TIMEOUT_SECONDS = 60;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 100_000;
    public static final String TRUNCATION_MARKER = "\n...[OUTPUT TRUNCATED]...";

    private final Path defaultWorkingDirectory;
    private final Path sandboxRoot;
    private final long timeoutSeconds;
    private final int maxOutputChars;
    private volatile ProcessHandle lastExecutedProcessHandle;

    public CommandExecutorService() {
        this(Paths.get("."), null, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_OUTPUT_CHARS);
    }

    public CommandExecutorService(Path defaultWorkingDirectory) {
        this(defaultWorkingDirectory, null, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_OUTPUT_CHARS);
    }

    public CommandExecutorService(long timeoutSeconds, int maxOutputChars) {
        this(Paths.get("."), null, timeoutSeconds, maxOutputChars);
    }

    public CommandExecutorService(Path defaultWorkingDirectory, long timeoutSeconds, int maxOutputChars) {
        this(defaultWorkingDirectory, null, timeoutSeconds, maxOutputChars);
    }

    public CommandExecutorService(Path defaultWorkingDirectory, Path sandboxRoot, long timeoutSeconds, int maxOutputChars) {
        this.defaultWorkingDirectory = (defaultWorkingDirectory != null) ? defaultWorkingDirectory : Paths.get(".");
        this.sandboxRoot = sandboxRoot;
        this.timeoutSeconds = (timeoutSeconds > 0) ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.maxOutputChars = (maxOutputChars > 0) ? maxOutputChars : DEFAULT_MAX_OUTPUT_CHARS;
    }

    public Path getDefaultWorkingDirectory() {
        return defaultWorkingDirectory;
    }

    public Path getSandboxRoot() {
        return sandboxRoot;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getMaxOutputChars() {
        return maxOutputChars;
    }

    public ProcessHandle getLastExecutedProcessHandle() {
        return lastExecutedProcessHandle;
    }

    /**
     * Executes a shell command using the default working directory.
     *
     * @param command raw shell command string
     * @return formatted execution result including exit code, stdout, and stderr
     */
    public String execute(String command) {
        return execute(command, this.defaultWorkingDirectory);
    }

    /**
     * Executes a shell command within a specified working directory string.
     *
     * @param command          raw shell command string
     * @param workingDirectory working directory path as a string
     * @return formatted execution result including exit code, stdout, and stderr
     */
    public String execute(String command, String workingDirectory) {
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return execute(command, this.defaultWorkingDirectory);
        }
        try {
            return execute(command, Paths.get(workingDirectory));
        } catch (Exception e) {
            return "Error: Invalid working directory path: " + workingDirectory + " (" + e.getMessage() + ")";
        }
    }

    /**
     * Executes a shell command within a validated working directory Path, enforcing process tree cleanup,
     * bounded stream draining, and timeouts.
     *
     * @param command          raw shell command string
     * @param workingDirectory target working directory
     * @return formatted execution result including exit code, stdout, and stderr
     */
    public String execute(String command, Path workingDirectory) {
        if (command == null || command.isBlank()) {
            return "Error: Command cannot be empty.";
        }

        Path targetDir = (workingDirectory != null) ? workingDirectory : defaultWorkingDirectory;
        Path resolvedDir;
        try {
            resolvedDir = targetDir.toAbsolutePath().normalize();
        } catch (Exception e) {
            return "Error: Invalid working directory path: " + targetDir + " (" + e.getMessage() + ")";
        }

        if (!Files.exists(resolvedDir)) {
            return "Error: Working directory does not exist: " + targetDir;
        }
        if (!Files.isDirectory(resolvedDir)) {
            return "Error: Working directory is not a directory: " + targetDir;
        }
        if (sandboxRoot != null) {
            Path normalizedSandbox = sandboxRoot.toAbsolutePath().normalize();
            if (!resolvedDir.startsWith(normalizedSandbox)) {
                return "Error: Working directory is outside sandbox root: " + targetDir;
            }
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(resolvedDir.toFile());

        if (isWindows) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("/bin/sh", "-c", command);
        }

        Process process = null;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            process = processBuilder.start();
            this.lastExecutedProcessHandle = process.toHandle();

            final Process procRef = process;
            Future<String> outputFuture = executor.submit(() -> gobbleStream(procRef.getInputStream(), maxOutputChars));
            Future<String> errorFuture = executor.submit(() -> gobbleStream(procRef.getErrorStream(), maxOutputChars));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                destroyProcessTree(process);
                outputFuture.cancel(true);
                errorFuture.cancel(true);
                return "Error: Command timed out after " + timeoutSeconds + " seconds.";
            }

            // Bounded grace period to drain remaining stream buffers without indefinite hang
            String output;
            try {
                output = outputFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                outputFuture.cancel(true);
                destroyProcessTree(process);
                output = "[STREAM DRAIN TIMEOUT]";
            }

            String error;
            try {
                error = errorFuture.get(2, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                errorFuture.cancel(true);
                destroyProcessTree(process);
                error = "[STREAM DRAIN TIMEOUT]";
            }

            int exitCode = process.exitValue();

            StringBuilder result = new StringBuilder();
            result.append("Exit Code: ").append(exitCode).append("\n");
            if (output != null && !output.isBlank()) {
                result.append("Output:\n").append(output).append("\n");
            }
            if (error != null && !error.isBlank()) {
                result.append("Error:\n").append(error);
            }

            return result.toString().trim();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            return "Command execution interrupted.";
        } catch (Exception e) {
            destroyProcessTree(process);
            return "Failed to execute command: " + e.getMessage();
        }
    }

    /**
     * Alias for execute(String command).
     */
    public String executeCommand(String command) {
        return execute(command);
    }

    /**
     * Alias for execute(String command, String workingDirectory).
     */
    public String executeCommand(String command, String workingDirectory) {
        return execute(command, workingDirectory);
    }

    /**
     * Alias for execute(String command, Path workingDirectory).
     */
    public String executeCommand(String command, Path workingDirectory) {
        return execute(command, workingDirectory);
    }

    /**
     * Forcibly destroys the entire process tree (descendants first, then root handle, then Process).
     *
     * @param process the root process whose tree must be terminated
     */
    public void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            ProcessHandle handle = process.toHandle();
            List<ProcessHandle> descendants = handle.descendants().toList();
            for (ProcessHandle descendant : descendants) {
                try {
                    descendant.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
            handle.destroyForcibly();
        } catch (Exception ignored) {
        } finally {
            try {
                process.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Reads an input stream asynchronously up to maxChars, and drains any remaining data
     * to prevent subprocess pipe buffer exhaustion.
     */
    private String gobbleStream(InputStream inputStream, int maxChars) {
        StringBuilder builder = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (!truncated) {
                    int remaining = maxChars - builder.length();
                    if (remaining > 0) {
                        int toAppend = Math.min(read, remaining);
                        builder.append(buffer, 0, toAppend);
                        if (toAppend < read) {
                            truncated = true;
                            builder.append(TRUNCATION_MARKER);
                        }
                    } else {
                        truncated = true;
                        builder.append(TRUNCATION_MARKER);
                    }
                }
                // When truncated == true, keep reading to EOF to drain the pipe buffer
            }
        } catch (Exception e) {
            builder.append("\nError reading stream: ").append(e.getMessage());
        }
        return builder.toString().trim();
    }
}
