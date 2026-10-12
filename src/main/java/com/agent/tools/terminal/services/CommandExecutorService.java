package com.agent.tools.terminal.services;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Service encapsulating OS-level process execution with concurrent stream gobbling and timeouts.
 */
public class CommandExecutorService {

    /**
     * Executes a shell command on the host OS with asynchronous stream gobbling and a 60-second timeout.
     *
     * @param command raw shell command string
     * @return formatted execution result including exit code, stdout, and stderr
     */
    public String execute(String command) {
        if (command == null || command.isBlank()) {
            return "Error: Command cannot be empty.";
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder processBuilder = new ProcessBuilder();

        if (isWindows) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("/bin/sh", "-c", command);
        }

        try {
            Process process = processBuilder.start();

            // Use Virtual Threads for lightweight stream gobbling
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<String> outputFuture = executor.submit(() -> gobbleStream(process.getInputStream()));
                Future<String> errorFuture = executor.submit(() -> gobbleStream(process.getErrorStream()));

                // Enforce a 60-second timeout
                boolean finished = process.waitFor(60, TimeUnit.SECONDS);

                if (!finished) {
                    process.destroyForcibly();
                    return "Error: Command timed out after 60 seconds.";
                }

                String output = outputFuture.get();
                String error = errorFuture.get();
                int exitCode = process.exitValue();

                StringBuilder result = new StringBuilder();
                result.append("Exit Code: ").append(exitCode).append("\n");
                if (output != null && !output.isBlank()) result.append("Output:\n").append(output).append("\n");
                if (error != null && !error.isBlank()) result.append("Error:\n").append(error);

                return result.toString().trim();
            }
        } catch (Exception e) {
            return "Failed to execute command: " + e.getMessage();
        }
    }

    private String gobbleStream(InputStream inputStream) {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
            }
        } catch (Exception e) {
            builder.append("Error reading stream: ").append(e.getMessage());
        }
        return builder.toString().trim();
    }
}
