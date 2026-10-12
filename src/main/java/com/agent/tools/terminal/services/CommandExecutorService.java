package com.agent.tools.terminal.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Service encapsulating OS-level ProcessBuilder execution.
 * Assumes security authorization has already been evaluated by the caller.
 */
public class CommandExecutorService {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutorService.class);
    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    /**
     * Executes a raw shell command on the host OS.
     *
     * @param command command to execute
     * @return combined process stdout/stderr output or error message
     */
    public String execute(String command) {
        if (command == null || command.isBlank()) {
            return "Error: Command cannot be empty.";
        }

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
}
